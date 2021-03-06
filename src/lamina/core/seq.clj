;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  lamina.core.seq
  (:use
    [lamina.core.channel]
    [lamina.core.pipeline]
    [clojure.contrib.generic.functor])
  (:require
    [lamina.core.observable :as o]
    [lamina.core.queue :as q])
  (:import
    [java.util.concurrent TimeoutException]
    [lamina.core.queue EventQueue]
    [lamina.core.channel Channel]))

;;;

(defn lazy-channel-seq
  "Creates a lazy-seq which consumes messages from the channel.  Only elements
   which are realized will be consumes.

   (take 1 (lazy-channel-seq ch)) will only take a single message from the channel,
   and no more.  If there are no messages in the channel, execution will halt until
   a message is enqueued.

   'timeout' controls how long (in ms) the sequence will wait for each element.  If
   the timeout is exceeded or the channel is closed, the sequence will end.  By default,
   the sequence will never time out."
  ([ch]
     (lazy-channel-seq ch -1))
  ([ch timeout]
     (let [timeout-fn (if (fn? timeout)
			timeout
			(constantly timeout))]
       (lazy-seq
	 (when-not (drained? ch)
	   (let [value (promise)]
	     (receive (poll {:ch ch} (timeout-fn))
	       #(deliver value
		  (when (first %)
		    [(second %)])))
	     (let [val @value]
	       (when (and val
		       (or
			 (not (drained? ch))
			 (not (nil? (first val)))))
		 (concat val (lazy-channel-seq ch timeout-fn))))))))))

(defn channel-seq
  "Creates a non-lazy sequence which consumes all messages from the channel within the next
   'timeout' milliseconds.  A timeout of 0, which is the default, will only consume messages
   currently within the channel.

   This call is synchronous, and will hang the thread until the timeout is reached or the channel
   is drained."
  ([ch]
     (channel-seq ch 0))
  ([ch timeout]
     (doall
       (lazy-channel-seq ch
	 (if (neg? timeout)
	   (constantly timeout)
	   (let [t0 (System/currentTimeMillis)]
	     #(max 0 (- timeout (- (System/currentTimeMillis) t0)))))))))

(defn wait-for-message
  "Synchronously consumes a single message from a channel.  If no message is received within the
   timeout, a java.util.concurrent.TimeoutException is thrown.  By default, this function will
   not time out."
  ([ch]
     (wait-for-message ch -1))
  ([ch timeout]
     (let [val (-> ch queue (q/dequeue ::none))]
       (if-not (= ::none val)
	 val
	 (let [result (promise)]
	  (receive (poll {:ch ch} timeout) #(deliver result %))
	  (if-let [result @result]
	    (second result)
	    (throw (TimeoutException. "Timed out waiting for message from channel."))))))))
;;;

(defn- sample-queue [ch finalizer]
  (let [q (-> ch ^EventQueue queue .q)]
    (dosync
      (ensure q)
      (let [msgs @q]
	(finalizer q)
	msgs))))

(defn receive-all
  [ch & callbacks]
  (cond
    (drained? ch)
    false

    (constant-channel? ch)
    (apply receive ch callbacks)

    :else
    (let [distributor (-> ch queue q/distributor)
	  send-to-callbacks (fn [msgs]
			      (doseq [msg msgs]
				(doseq [c callbacks]
				  (c msg))))]
      (o/lock-observable distributor
	(when (closed? ch) 
	  (send-to-callbacks
	    (butlast
	      (sample-queue ch
		#(ref-set %
		   (if (empty? (deref %))
		     clojure.lang.PersistentQueue/EMPTY
		     (conj clojure.lang.PersistentQueue/EMPTY (last (deref %)))))))))
	(send-to-callbacks
	  (sample-queue ch
	    #(ref-set % clojure.lang.PersistentQueue/EMPTY)))
	(when-not (drained? ch)
	  (o/subscribe distributor
	    (zipmap
	      callbacks
	      (map
		(fn [f]
		  (o/observer
		    #(doseq [m %] (f m))
		    #(f nil)
		    nil))
		callbacks)))))
      (q/check-for-drained (queue ch))
      true)))

(defn siphon
  [source destination-function-map]
  (cond
    (drained? source)
    false

    (constant-channel? source)
    (do
      (receive source
	#(doseq [[dst f] destination-function-map]
	   (let [msg (first (f %))]
	     (enqueue dst msg))))
      true)

    :else
    
    (let [distributor ^Observable (-> source queue q/distributor)
	  send-to-destinations (fn [msgs]
				 (doseq [[dst f] destination-function-map]
				   (apply enqueue dst (f msgs))))]
      (o/lock-observable distributor
	(send-to-destinations
	  (sample-queue source
	    #(ref-set % clojure.lang.PersistentQueue/EMPTY)))
	(o/siphon
	  distributor
	  (zipmap
	    (map consumer (keys destination-function-map))
	    (vals destination-function-map))
	  2
	  false))
      (q/check-for-drained (queue source))
      true)))

;;;

(defn fork
  "Creates one or many exact copies of 'ch'.  Messages enqueued into the original channel
   will appear in all copies, but can be consumed separately.  This allows for multiple
   consumers to receive the same stream at different rates.

   Any message enqueued into one channel will be enqueued into all other copies."
  ([ch]
     (first (fork 1 ch)))
  ([n ch]
     (cond
       (drained? ch)
       (repeat n ch)

       (constant-channel? ch)
       (repeat n ch)

       :else
       (o/lock-observable (-> ch queue q/source)
	 (doall
	   (map
	     (fn [_]
	       (let [o (o/observable)]
		 (o/siphon (-> ch queue q/source) {o identity} -1 true)
		 (Channel. o (q/copy-queue (queue ch) o))))
	     (range n)))))))

(defn receive-in-order
  "Consumes messages from a channel one at a time.  The callback will only receive the next
   message once it has completed processing the previous one.  If the callback returns a result
   channel, the next message will not be received until there is a result.

   This is a lossy iteration over the channel.  Fork the channel if there is another consumer."
  [ch f]
  (if (drained? ch)
    (success-result nil)
    (run-pipeline ch
      read-channel
      (fn [msg]
	(when-not (and (nil? msg) (drained? ch))
	  (f msg)))
      (fn [_]
	(when-not (drained? ch)
	  (restart))))))

(defn map*
  "Returns a channel which will consume all messages from 'ch', and emit (f msg)."
  [f ch]
  (let [ch* (channel)]
    (siphon ch
      {ch* #(if (and (drained? ch) (= [nil] %))
	      %
	      (map f %))})
    ch*))

(defn filter*
  "Returns a channel which will consume all messages from 'ch', but only emit messages
   for which (f msg) is true."
  [f ch]
  (let [ch* (channel)]
    (siphon ch
      {ch* #(if (and (drained? ch) (= [nil] %))
	      %
	      (filter f %))})
    ch*))

(defn take*
  "Returns a channel which will consume 'n' messages from 'ch'."
  [n ch]
  (let [ch* (channel)
	cnt (ref n)]
    (listen ch
      (fn [msg]
	[(pos? (alter cnt dec))
	 (let [zero-cnt? (zero? @cnt)]
	   #(do
	      (enqueue ch* %)
	      (when zero-cnt?
		(close ch*))))]))
    ch*))

(defn take-while*
  "Returns a channel which will consume messages from 'ch' until (f msg) is false."
  [f ch]
  (let [ch* (channel)
	cnt (ref 0)
	cnt* (atom 0)
	final (atom nil)]
    (listen ch
      (fn [msg]
	(if-not (f msg)
	  (do
	    (reset! final (ensure cnt))
	    nil)
	  (do
	    (alter cnt inc)
	    [true (fn [msg]
		    (let [cnt* (swap! cnt* inc)]
		      (enqueue ch* msg)
		      (when-let [final @final]
			(when (= final cnt*)
			  (close ch*)))))]))))
    ch*))

(defn- reduce- [f val ch]
  (run-pipeline val
    (read-merge
      #(read-channel ch)
      #(if (and (nil? %2) (drained? ch))
	 %1
	 (f %1 %2)))
    (fn [val]
      (if (drained? ch)
	val
	(restart val)))))

(defn reduce*
  "Returns a constant-channel which will return the result of the reduce once the channel has been exhausted."
  ([f ch]
     (:success
       (run-pipeline ch
	 read-channel
	 #(reduce- f %1 ch))))
  ([f val ch]
     (:success (reduce- f val ch))))

(defn reductions- [f val ch]
  (let [ch* (channel)]
    (enqueue ch* val)
    (run-pipeline val
      (read-merge
	#(read-channel ch)
	#(if (and (nil? %2) (drained? ch))
	   nil
	   (f %1 %2)))
      (fn [val]
	(if (drained? ch)
	  (when val
	    (enqueue-and-close ch* val))
	  (do
	    (enqueue ch* val)
	    (restart val)))))
    ch*))

(defn reductions*
  "Returns a channel which contains the intermediate results of the reduce operation."
  ([f ch]
     (wait-for-message
       (:success
	 (run-pipeline ch
	   read-channel
	   #(reductions- f %1 ch)))))
  ([f val ch]
     (reductions- f val ch)))
