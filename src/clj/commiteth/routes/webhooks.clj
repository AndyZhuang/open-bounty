(ns commiteth.routes.webhooks
  (:require [cheshire.core :as json]
            [clojure.string :as str :refer [join]]
            [clojure.tools.logging :as log]
            [commiteth.bounties :as bounties]
            [commiteth.db
             [issues :as issues]
             [pull-requests :as pull-requests]
             [repositories :as repos]
             [users :as users]]
            [commiteth.github.core :as github]
            [commiteth.util.digest :refer [hex-hmac-sha1]]
            [compojure.core :refer [defroutes POST]]
            [crypto.equality :as crypto]
            [ring.util.http-response :refer [ok]])
  (:import java.lang.Integer))

(defn find-issue-event
  [events type owner]
  (first (filter #(and
                   (= owner (get-in % [:actor :login]))
                   (= type (:event %)))
                 events)))


(defn labeled-as-bounty?
  [action issue]
  (and
   (= "labeled" action)
   (= bounties/label-name (get-in issue [:label :name]))))

(defn find-commit-id
  [user repo issue-number event-types]
  (log/debug "find-commit-id" user repo issue-number event-types)
  (some identity (map #(->
                        (github/get-issue-events user repo issue-number)
                        (find-issue-event % user)
                        :commit_id)
                      event-types)))


(defn handle-issue-labeled
  [webhook-payload]
  (log/debug "handle-issue-labeled")
  (let [{issue :issue} webhook-payload
        {repo-id :id
         repo-name :name
         {login :login} :owner} (:repository webhook-payload)
        repo-map {:repo repo-name :login login :repo_id repo-id}]
    (bounties/add-bounty-for-issue repo-name repo-id login issue)))

(defn handle-issue-closed
  ;; TODO: does not work in case the issue is closed on github web ui
  [{{{user :login} :owner repo :name}   :repository
    {issue-id :id issue-number :number} :issue}]
  (log/debug "handle-issue-closed")
  (future
    (when-let [commit-id (find-commit-id user repo issue-number ["referenced" "closed"])]
      (log/debug (format "Issue %s/%s/%s closed with commit %s" user repo issue-number commit-id))
      (issues/close commit-id issue-id))))

(def ^:const keywords
  [#"(?i)close:?\s+#(\d+)"
   #"(?i)closes:?\s+#(\d+)"
   #"(?i)closed:?\s+#(\d+)"
   #"(?i)fix:?\s+#(\d+)"
   #"(?i)fixes:?\s+#(\d+)"
   #"(?i)fixed:?\s+#(\d+)"
   #"(?i)resolve:?\s?#(\d+)"
   #"(?i)resolves:?\s+#(\d+)"
   #"(?i)resolved:?\s+#(\d+)"])

(defn extract-issue-number
  [pr-body pr-title]
  (let [extract (fn [source]
                  (mapcat #(keep
                            (fn [s]
                              (try (let [issue-number (Integer/parseInt (second s))]
                                     (when (pos? issue-number)
                                       issue-number))
                                   (catch NumberFormatException _)))
                            (re-seq % source)) keywords))]
    (concat (extract pr-body)
            (extract pr-title))))


(defn ensure-bounty-issue
  "Checks if an issue has a bounty label attached and returns its number"
  [user repo issue-number]
  (when-let [issue (github/get-issue user repo issue-number)]
    (when (bounties/has-bounty-label? issue)
      issue-number)))


(defn handle-pull-request-event
  ;; when a PR is opened, only consider it as a claim if:
  ;; * PR references an existing bounty-issue
  ;;
  ;; when a PR is merged via close event, only consider it a bounty
  ;; claim being accepted if:
  ;; * PR exists in DB
  ;; * PR references an existing bounty-issue
  [event-type
   {{{owner :login} :owner
     repo           :name
     repo-id        :id}    :repository
    {{user-id    :id
      login      :login
      avatar_url :avatar_url
      name       :name} :user
     id              :id
     pr-number       :number
     pr-body         :body
     pr-title        :title} :pull_request}]
  (log/debug "handle-pull-request-event" event-type owner repo repo-id login pr-body pr-title)
  (log/debug (extract-issue-number pr-body pr-title))
  (when-let [bounty-issue-number (->>
                                  (extract-issue-number pr-body pr-title)
                                  (first)
                                  (ensure-bounty-issue owner repo))]
    (log/debug "Referenced bounty issue found" bounty-issue-number)
    (users/create-user user-id login name nil avatar_url nil)
    (let [pr-data {:repo_id   repo-id
                   :pr_id     id
                   :pr_number pr-number
                   :user_id   user-id
                   :issue_number bounty-issue-number
                   :state event-type}]
      (case event-type
        :opened (do
                  (log/info "PR with reference to bounty issue"
                            bounty-issue-number "opened")
                  (pull-requests/save (merge pr-data {:state :opened
                                                      :commit_id nil})))
        :closed (if-let [commit-id (find-commit-id owner
                                                   repo
                                                   pr-number
                                                   ["merged"])]
                  (do (log/info "PR with reference to bounty issue"
                                bounty-issue-number "merged")
                      (pull-requests/save
                       (merge pr-data {:state :merged
                                       :commit_id commit-id})))
                  (do (log/info "PR with reference to bounty issue"
                                bounty-issue-number "closed with no merge")
                      (pull-requests/save
                       (merge pr-data {:state :closed
                                       :commit_id nil}))))))))


(defn handle-issue
  [webhook-payload]
  (when-let [action (:action webhook-payload)]
    (log/debug "handle-issue" action)
    (when (labeled-as-bounty? action webhook-payload)
      (handle-issue-labeled webhook-payload))
    (when (and
           (= "closed" action)
           (bounties/has-bounty-label? (:issue webhook-payload)))
      (handle-issue-closed webhook-payload)))
  (ok))


(defn handle-pull-request
  [pull-request]
  (case (:action pull-request)
    "opened" (handle-pull-request-event :opened pull-request)
    "closed" (handle-pull-request-event :closed pull-request)
    nil)
  (ok))


(defn validate-secret [webhook-payload raw-payload github-signature]
  (let [full-name (get-in webhook-payload [:repository :full_name])
        repo (repos/get-repo full-name)
        secret (:hook_secret repo)
        signature (str "sha1=" (hex-hmac-sha1 secret raw-payload))]
    (crypto/eq? signature github-signature)))


(defroutes webhook-routes
  (POST "/webhook" {:keys [headers body]}
        (let [raw-payload (slurp body)
              payload (json/parse-string raw-payload true)]
          (if (validate-secret payload raw-payload (get headers "x-hub-signature"))
            (do
              (log/debug "Github secret validation OK")
              (log/debug "x-github-event" (get headers "x-github-event"))
              (case (get headers "x-github-event")
                "issues" (handle-issue payload)
                "pull_request" (handle-pull-request payload)
                (ok)))))))
