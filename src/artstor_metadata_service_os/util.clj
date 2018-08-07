(ns artstor-metadata-service-os.util
  (:require [clojure.tools.logging :as log]
            [clj-sequoia.service :refer [get-hosts get-env]]
            [environ.core :refer [env]]))

(defn resolve [app-name]
  (try
    (let [service (rand-nth (get-hosts (get-env) app-name))]
      (str "http://" service ":8080/"))
    (catch Exception e
      (log/error "Couldn't talk to eureka, building local string.")
      (str "http://" app-name ".apps.test.cirrostratus.org/"))))

(defn build-service-url [app-name path]
  (str (resolve app-name) path))
