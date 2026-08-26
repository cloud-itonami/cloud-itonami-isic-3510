(ns open-denki.app-test
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [open-denki.app :as app]))

(use-fixtures :each
  {:before (fn [] (rf/clear-subscription-cache!) (reset! rf-db/app-db {}))})

(deftest initialize-db-sets-defaults
  (testing ":initialize-db populates every fact the Svelte scaffold held"
    (rf/dispatch-sync [:initialize-db])
    (is (= app/default-db @rf-db/app-db))
    (is (= "Worker" @(rf/subscribe [:app/title])))
    (is (= "worker" @(rf/subscribe [:app/name])))
    (is (= "etzhayyim-project-open-denki" @(rf/subscribe [:app/project])))
    (is (= "worker" @(rf/subscribe [:app/kind])))
    (is (= 1 @(rf/subscribe [:app/route-count])))
    (is (= ["open-denki.etzhayyim.com/*"]
           @(rf/subscribe [:app/routes])))
    (is (= ["AGENTGATEWAY_MCP_ROUTER_URL" "APP_FRAMEWORK" "APP_HANDLE" "PRIMARY_DID"]
           @(rf/subscribe [:app/vars])))
    (is (false? @(rf/subscribe [:app/xrpc?])))
    (is (= "worker/cljs/src/open_denki/app.cljs" @(rf/subscribe [:app/relative-path])))))

(deftest routes-sub-reflects-db
  (testing ":app/routes reads whatever is in the db, not a fixed value"
    (reset! rf-db/app-db {:app/routes ["only-one.example.com/*"]})
    (is (= ["only-one.example.com/*"] @(rf/subscribe [:app/routes])))))

(deftest vars-sub-reflects-db
  (testing ":app/vars reads whatever is in the db, not a fixed value"
    (reset! rf-db/app-db {:app/vars []})
    (is (= [] @(rf/subscribe [:app/vars])))))

(deftest xrpc-sub-reflects-db
  (testing ":app/xrpc? reads whatever is in the db, not a fixed value"
    (reset! rf-db/app-db {:app/xrpc? true})
    (is (true? @(rf/subscribe [:app/xrpc?])))))

(deftest initialize-db-overwrites-prior-state
  (testing ":initialize-db resets to defaults even if the db already had other data"
    (reset! rf-db/app-db {:app/title "stale" :app/xrpc? true :unrelated 42})
    (rf/dispatch-sync [:initialize-db])
    (is (= app/default-db @rf-db/app-db))))
