(ns ca.clojurist.autoremote
  "Send AutoRemote messages and notifications to Android devices."
  {:author "Robert Medeiros" :email "robert@clojurist.ca"}
  (:require [clojure.set])
  (:require [org.bovinegenius.exploding-fish :as uri]
            [org.httpkit.client :as http]
            [validateur.validation :refer :all]))

;; DATA ------

(def api-key-length
  "The expected length in characters of the API key string."
  183)

(def url-base
  "The base URL of the AutoRemote service."
  "http://autoremotejoaomgcd.appspot.com")

(def url-path-notification
  "The API resource path for sending notifications."
  "/sendnotification")

(def url-path-message
  "The API resource path for sending messages."
  "/sendmessage")

(def url-regex
  "A regular expression for matching URLs."
  #"(?:https?:\/\/)?(?:[0-9a-z\.\-]+)\.(?:[a-z]{2,6})(?:[\/\w\.\-]*)*\/?")

(def message-keys
  "The set of allowed map keys in the message URL query parameter
map."
  #{ ;; The text you want to send.
    :message

    ;; (optional) Sets the Target on this message.
    :target

    ;; (optional) The device that receives this message will reply to
    ;; this device. Key when choosing "Last Sender" in the devices list.
    :sender

    ;; (optional) This sets the "Act as Sender" device's channel.
    :channel

    ;; (optional) The password you have configured on your device.
    :password

    ;; (optional) Time in seconds AutoRemote will try to deliver the
    ;; message for before giving up.
    :ttl
    })

(def notification-keys
  "The set of allowed map keys in the notification URL query parameter map."
  #{ ;; The title of the notification.
    :title

    ;; The text of the notification.
    :text

    ;; One of 10 notification sounds specified in the AutoRemote
    ;; settings [1,10].
    :sound

    ;; A list of integers representing a Tasker vibration pattern.
    :vibration

    ;; Opened when the notification is touched (overridden
    ;; by :action).
    :url

    ;; Notifications with different IDs will not overlap each other.
    :id

    ;; The action (can be in the param1 param2=:=command format) that
    ;; will be executed on Notification touch.
    :action

    ;; The notification icon URL. Will only work on Android 3.0 and above.
    :icon

    ;; #RRGGBB, #AARRGGBB, 'red', 'blue', 'green', 'black', 'white',
    ;; 'gray', 'cyan', 'magenta', 'yellow', 'lightgray', 'darkgray'.
    :led

    ;; Time in milliseconds the LED will be on during blinking.
    :ledon

    ;; Time in milliseconds the LED will be off during blinking.
    :ledoff

    ;; Image shown on "Big Picture" notifications (Android 4.1+).
    :picture

    ;; Action on Receive, Action (can be in the param1
    ;; param2=:=command format) that will automatically execute when
    ;; receiving this notification.
    :message

    ;; Add Share button(s) on Jelly Bean notifications. Input any
    ;; value to show these buttons. Leave blank otherwise.
    :share

    ;; Action Button 1, AutoRemote action (can be in the param1
    ;; param2=:=command format) that will be available in the form of
    ;; a button on Jelly Bean.
    :action1

    ;; Action Button Label 1, label for Action Button 1.
    :action1name

    ;; Go to AutoRemote Notification action in Tasker and click the
    ;; Button 1 Icon field. There you can see the possible values for
    ;; this field.
    :action1icon

    ;; Action Button 2, AutoRemote action (can be in the param1
    ;; param2=:=command format) that will be available in the form of
    ;; a button on Jelly Bean.
    :action2

    ;; Action Button Label 2, label for Action Button 2.
    :action2name

    ;; Go to AutoRemote Notification action in Tasker and click the
    ;; Button 2 Icon field. There you can see the possible values for
    ;; this field.
    :action2icon

    ;; Action Button 3, AutoRemote action (can be in the param1
    ;; param2=:=command format) that will be available in the form of
    ;; a button on Jelly Bean.
    :action3

    ;; Action Button Label 3, label for Action Button 3.
    :action3name

    ;; Go to AutoRemote Notification action in Tasker and click the
    ;; Button 3 Icon field. There you can see the possible values for
    ;; this field.
    :action3icon

    ;; Fill in any value to make the notification persistent.
    :persistent

    ;; Go to AutoRemote Notification action in Tasker and click the
    ;; Status Bar Icon field. There you can see the possible values for
    ;; this field.
    :statusbaricon

    ;; Text to appear on the status bar when the notification is first
    ;; created. Defaults to the :text field above.
    :ticker

    ;; Fill any value to make the notification dismiss itself when
    ;; touched.
    :dismissontouch

    ;; Values ranging from -2 (min priority) to 2 [max priority].
    :priority

    ;; Number to appear on the lower right of the Notification.
    :number

    ;; Small string to appear on the lower right of the Notification;;
    ;; overrides :number.
    :contentinfo

    ;; Sub Text of the notification.
    :subtext

    ;; Max value the progress can have.
    :maxprogress

    ;; Value from 0 to the value you set in :maxprogress.
    :progress

    ;; If set, an indeterminate progress bar will be used.
    :indeterminateprogress

    ;; AutoRemote Message that will be executed when the notification
    ;; is dismissed.
    :actionondismiss

    ;; Fill any value to cancel notification with the given id. Must
    ;; fill id to cancel; all other settings besides id will be
    ;; ignored.
    :cancel

    ;; (optional) The device that receives this message will reply to
    ;; this device key when choosing "Last Sender" in the devices list.
    :sender

    ;; (optional) The password you have configured on your device.
    :password

    ;; (optional) Time in seconds AutoRemote will try to deliver the
    ;; message for before giving up.
    :ttl

    ;; (optional) If the receiving device is unreachable, only one
    ;; message in a message group will be delivered. Useful you if
    ;; e.g. leave a device in airplane mode at night and only want to
    ;; receive the last of the messages that were sent during that
    ;; time. Leave blank to deliver all messages.
    :collapseKey
    })

;; ---------------
;; INTERNAL ------
;; ---------------

;; Does this look like a potentially valid AutoRemote device key?
(defn- valid-api-key?
  [key]
  (and
   (string? key)
   (= api-key-length (count key))))

;; Given a URL and a map of key/value pairs, return the URL with the
;; map contents added to the URL as query parameters."
(defn- add-query-params
  [input-url params]
  {:pre [(map? params) (every? keyword? (keys params))]}
  ;; Turn map keys from keywords into strings, as required when
  ;; generating the query string to append to the URL.
  (let [keywords-to-strings (fn [[k v]] [(name k) v])
        query-params (into {} (map keywords-to-strings params))
        add-param (fn [url [key val]] (uri/param url key val))]
    (reduce add-param input-url query-params)))

;; Return the base URL for the AutoRemote service with the given path
;; (thus determining what time of communication to send) and the API
;; key appended as a query parameter.
(defn- prepare-url
  [key path params]
  {:pre [(string? path) (valid-api-key? key) (map? params)]}
  (let [url-base (uri/uri url-base)
        ;; The path determines if we send a message or notification.
        url-with-path (uri/path url-base path)
        ;; Add the 'key' query parameter.
        url-with-key (uri/param url-with-path "key" key)
        ;; Add the remaining query parameters.
        url-with-params (add-query-params url-with-key params)]
    (str url-with-params)))

;; VALIDATION ------

(defn- validation-result?
  "Check the result returned by a home-grown validation function for
validateur to ensure it conforms to the library requirements."
  [result]
  (and
   (vector? result)
   (let [[a b] result]
     (or (true? a) (false? a))
     (map? b))))

(defn- url-of
  "A 'validateur' validation function for checking that a map value is
a URL."
  [attribute & {:keys [allow-nil message blank-message]
                :or {allow-nil false
                     message "must be a URL"
                     blank-message "can't be blank"}}]
  (let [f (if (vector? attribute) get-in get)]
    (fn [m]
      {:post [(validation-result? %)]}
      (let [v (f m attribute)]
        (if (and (nil? v) (not allow-nil))
          [false {attribute #{blank-message}}]
          ;; TODO: is there a nicer way to handle the nil condition?
          (if (or (nil? v) (re-matches url-regex (str v)))
            [true {}]
            [false {attribute #{(str message)}}]))))))

(def validate-notification-params
  "A 'validateur' validation function for 'notification' query
parameter map."
  (validation-set
   (all-keys-in notification-keys)
   (presence-of :title)
   (presence-of :text)
   (numericality-of :sound :only-integer true :gte 1 :lte 10 :allow-nil true)
   (format-of :vibration :format #"^(?:[0-9]+\,?)+$" :allow-blank true :allow-nil true)
   (url-of :url :allow-nil true)
   (url-of :icon :allow-nil true)
   (format-of :led :format #"\b(?:red|blue|green|black|white|gray|cyan|magenta|yellow|lightgray|darkgray|#\d{6}|#\d{8})\b" :allow-blank true :allow-nil true)
   (numericality-of :ledon :only-integer true :gte 0 :allow-nil true)
   (numericality-of :ledoff :only-integer true :gte 0 :allow-nil true)
   (url-of :picture :allow-nil true)))

(def validate-message-params
  "A 'validateur' validation function for 'message' query parameter
map."
  (validation-set
   (all-keys-in message-keys)
   (presence-of :message)
   (numericality-of :ttl :only-integer true :gte 0 :allow-nil true)))

;; API ------

(defn url-message
  "Return the URL used to send an AutoRemote message."
  [key params]
  {:pre [(valid-api-key? key) (map? params)]}
  (prepare-url key url-path-message params))

(defn send-message
  "Send an AutoRemote message."
  [key & args]
  {:pre [(valid-api-key? key)]}
  (let [params (apply hash-map args)]
    (if-let [errors (invalid? validate-message-params params)]
      ;; Validator fn returns [t/f {:attr #{"error desc"}}].
      (validate-message-params params)
      @(http/post (url-message key params)))))

(defn url-notification
  "Return the URL used to send an AutoRemote notification."
  [key params]
  {:pre [(valid-api-key? key) (map? params)]}
  (prepare-url key url-path-notification params))

(defn send-notification
  "Send an AutoRemote notification."
  [key & args]
  {:pre [(valid-api-key? key)]}
  (let [params (apply hash-map args)]
    (if-let [errors (invalid? validate-notification-params params)]
      ;; Validator fn returns [t/f {:attr #{"error desc"}}].
      (validate-notification-params params)
      @(http/post (url-notification key params)))))

(defn url-to-key
  "Given an AutoRemote 'personal' URL, return the device key that it
  maps to."
  [url]
  {:pre [(string? url)]}
  ;; Request the URL and check the redirect (expected status:
  ;; 301). The key should be contained in the Location header URL.
  (let [response @(http/get url)]
    (if (= 301 (:status response))
      (let [location-string (get-in response [:headers :location])
            location-url (uri/uri location-string)
            key (uri/param location-url "key")]
        key))))

(defn sent?
  "Given a Ring response, return true if message/notification was
sent, false otherwise."
  [result]
  {:pre [(map? result)]}
  (= 200 (:status result)))
