;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth.register
  (:require
   [app.common.spec :as us]
   [app.config :as cf]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.auth.login :as login]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(mf/defc demo-warning
  [_]
  [:& msgs/inline-banner
   {:type :warning
    :content (tr "auth.demo-warning")}])

;; --- PAGE: Register

(defn- validate
  [data]
  (let [password (:password data)
        terms-privacy (:terms-privacy data)]
    (cond-> {}
      (> 8 (count password))
      (assoc :password {:message "errors.password-too-short"})

      (and (not terms-privacy) false)
      (assoc :terms-privacy {:message "errors.terms-privacy-agreement-invalid"}))))

(s/def ::fullname ::us/not-empty-string)
(s/def ::password ::us/not-empty-string)
(s/def ::email ::us/email)
(s/def ::invitation-token ::us/not-empty-string)
(s/def ::terms-privacy ::us/boolean)

(s/def ::register-form
  (s/keys :req-un [::password ::email]
          :opt-un [::invitation-token]))

(defn- handle-prepare-register-error
  [form error]
  (case (:code error)
    :registration-disabled
    (st/emit! (dm/error (tr "errors.registration-disabled")))

    :email-has-permanent-bounces
    (let [email (get @form [:data :email])]
      (st/emit! (dm/error (tr "errors.email-has-permanent-bounces" email))))

    :email-already-exists
    (swap! form assoc-in [:errors :email]
           {:message "errors.email-already-exists"})

    (st/emit! (dm/error (tr "errors.generic")))))

(defn- handle-prepare-register-success
  [_form {:keys [token] :as result}]
  (st/emit! (rt/nav :auth-register-validate {} {:token token})))

(mf/defc register-form
  [{:keys [params] :as props}]
  (let [initial (mf/use-memo (mf/deps params) (constantly params))
        form    (fm/use-form :spec ::register-form
                             :validators [validate]
                             :initial initial)
        submitted? (mf/use-state false)

        on-submit
        (mf/use-callback
         (fn [form _event]
           (reset! submitted? true)
           (let [params (:clean-data @form)]
             (->> (rp/mutation :prepare-register-profile params)
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs (partial handle-prepare-register-success form)
                           (partial handle-prepare-register-error form))))))
        ]


    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:type "email"
                    :name :email
                    :tab-index "2"
                    :help-icon i/at
                    :label (tr "auth.email")}]]
     [:div.fields-row
      [:& fm/input {:name :password
                    :tab-index "3"
                    :hint (tr "auth.password-length-hint")
                    :label (tr "auth.password")
                    :type "password"}]]

     [:& fm/submit-button
      {:label (tr "auth.register-submit")
       :disabled @submitted?}]]))

(mf/defc register-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:h1 (tr "auth.register-title")]
   [:div.subtitle (tr "auth.register-subtitle")]

   (when (contains? @cf/flags :demo-warning)
     [:& demo-warning])

   [:& register-form {:params params}]

    (when login/show-alt-login-buttons?
      [:*
       [:span.separator (tr "labels.or")]

       [:div.buttons
        [:& login/login-buttons {:params params}]]])

   [:div.links
    [:div.link-entry
     [:span (tr "auth.already-have-account") " "]
     [:a {:on-click #(st/emit! (rt/nav :auth-login {} params))
          :tab-index "4"}
      (tr "auth.login-here")]]

    (when (contains? @cf/flags :demo-users)
      [:div.link-entry
       [:span (tr "auth.create-demo-profile") " "]
       [:a {:on-click #(st/emit! (du/create-demo-profile))
            :tab-index "5"}
        (tr "auth.create-demo-account")]])]])

;; --- PAGE: register validation

(defn- handle-register-error
  [form error]
  (case (:code error)
    :registration-disabled
    (st/emit! (dm/error (tr "errors.registration-disabled")))

    :email-has-permanent-bounces
    (let [email (get @form [:data :email])]
      (st/emit! (dm/error (tr "errors.email-has-permanent-bounces" email))))

    :email-already-exists
    (swap! form assoc-in [:errors :email]
           {:message "errors.email-already-exists"})

    (do
      (println (:explain error))
      (st/emit! (dm/error (tr "errors.generic"))))))

(defn- handle-register-success
  [_form data]
  (cond
    (some? (:invitation-token data))
    (let [token (:invitation-token data)]
      (st/emit! (rt/nav :auth-verify-token {} {:token token})))

    (not= "penpot" (:auth-backend data))
    (st/emit! (du/login-from-register))

    :else
    (st/emit! (rt/nav :auth-register-success {} {:email (:email data)}))))

(s/def ::accept-terms-and-privacy (s/and ::us/boolean true?))
(s/def ::accept-newsletter-subscription ::us/boolean)

(s/def ::register-validate-form
  (s/keys :req-un [::token ::fullname ::accept-terms-and-privacy]
          :opt-un [::accept-newsletter-subscription]))

(mf/defc register-validate-form
  [{:keys [params] :as props}]
  (let [initial (mf/use-memo
                 (mf/deps params)
                 (fn []
                   (assoc params :accept-newsletter-subscription false)))
        form    (fm/use-form :spec ::register-validate-form
                             :initial initial)
        submitted? (mf/use-state false)

        on-submit
        (mf/use-callback
         (fn [form _event]
           (reset! submitted? true)
           (let [params (:clean-data @form)]
             (->> (rp/mutation :register-profile params)
                  (rx/finalize #(reset! submitted? false))
                  (rx/subs (partial handle-register-success form)
                           (partial handle-register-error form))))))
        ]

    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:name :fullname
                    :tab-index "1"
                    :label (tr "auth.fullname")
                    :type "text"}]]
     [:div.fields-row
      [:& fm/input {:name :accept-terms-and-privacy
                    :class "check-primary"
                    :label (tr "auth.terms-privacy-agreement")
                    :type "checkbox"}]]

     (when (contains? @cf/flags :newsletter-registration-check)
       [:div.fields-row
        [:& fm/input {:name :accept-newsletter-subscription
                      :class "check-primary"
                      :label (tr "auth.newsletter-subscription")
                      :type "checkbox"}]])

     [:& fm/submit-button
      {:label (tr "auth.register-submit")
       :disabled @submitted?}]]))


(mf/defc register-validate-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:h1 (tr "auth.register-title")]
   [:div.subtitle (tr "auth.register-subtitle")]

   [:& register-validate-form {:params params}]

   [:div.links
    [:div.link-entry
     [:a {:on-click #(st/emit! (rt/nav :auth-register {} {}))
          :tab-index "4"}
      (tr "labels.go-back")]]]])

(mf/defc register-success-page
  [{:keys [params] :as props}]
  [:div.form-container
   [:div.notification-icon i/icon-verify]
   [:div.notification-text (tr "auth.verification-email-sent")]
   [:div.notification-text-email (:email params "")]
   [:div.notification-text (tr "auth.check-your-email")]])

