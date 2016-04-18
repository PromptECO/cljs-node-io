(ns cljs-node-io.file "a port of java.io.File's reified files to node"
  (:import goog.Uri)
  (:require [cljs.nodejs :as nodejs :refer [require]]
            [cljs.reader :refer [read-string]]
            [cljs.core.async :as async :refer [put! take! chan <! pipe  alts!]]
            [cljs-node-io.streams :refer [FileInputStream FileOutputStream]]
            [cljs-node-io.protocols
              :refer [Coercions as-url as-file IFile IFileOutputStream
                      IOFactory make-reader make-writer make-input-stream make-output-stream]]))

(def fs (require "fs"))
(def path (require "path"))
(def os (require "os"))


(defn directory?
  "@param {string} pathstring
   @return {boolean} iff abstract pathname exists and is a directory"
  ^boolean
  [^String pathstring]
  (assert (string? pathstring) "directory? takes a string, perhaps you passed a file instead")
  (let [stats (try (.statSync fs pathstring) (catch js/Error e false))]
    (if stats
      (.isDirectory stats)
      false)))

(defn file?
  "@param {string} pathstring
   @return {boolean} iff abstract pathname exists and is a file"
  ^boolean
  [^String pathstring]
  (assert (string? pathstring) "file? takes a string, perhaps you passed a file instead")
  (let [stats (try (.statSync fs pathstring) (catch js/Error e false))]
    (if stats
      (.isFile stats)
      false)))

(defn get-non-dirs
  "@param {string} pathstring
  returns sequence of strings representing non-existing directory components
   of the passed pathstring, root first, in order "
  [^String pathstring]
  (reverse (take-while #(not (directory? %)) (iterate #(.dirname path %) pathstring))))

(defn ^boolean append? [opts] (boolean (:append opts)))

(defn filepath-dispatch
  ([x] (type x)) ; string || Uri.
  ([x y] (mapv type [x y])))

(defmulti  filepath "signature->File" filepath-dispatch)
(defmethod filepath Uri [u] (.getPath u))
(defmethod filepath js/String  [pathstring] pathstring)
(defmethod filepath [js/String js/String] [parent-str child-str] (str parent-str (.-sep path) child-str))
(defmethod filepath [:File js/String] [parent-file child-str] (str (.getPath parent-file) (.-sep path) child-str))
(defmethod filepath :default [x] (throw (js/Error.
                                         (str "Unrecognized path configuration passed to File constructor."
                                              "\nYou passed " (pr-str x)
                                              "\nYou must pass a [string], [uri], [string string], or [file string].\n" ))))

(defn file-stream-reader [filestream opts]
  (make-reader filestream opts)) ;just defering to file stream object for now

(defn file-stream-writer [filestream opts]
  (make-writer filestream opts)) ;just defering to file stream object for now

(defn file-reader
  "For async and sync opts, this builds an appropriate read method and attaches
   it to the reified file, returning the passed file.
    - opts = :encoding :async :stream?
    - if :stream? true, returns FileInputStream (as distinct object), :async is ignored
    - if :async? true, file.read() returns a chan which receives err|str on successful read
    - if :async? false, file.read() returns data synchronously"
  [file opts]
  (if (:stream? opts)
    (file-stream-reader (make-input-stream file opts) opts)
    (if (:async? opts)
      (specify! file Object
        (read [this]
          (let [c (chan) ]
            (.readFile fs (.getPath this) (or (:encoding opts) "utf8") ;if no encoding, returns buffer
              (fn [err data]
                (put! c (if err err data))))
            c)))
      ;sync reader
      (specify! file Object
        (read [this]
          (.readFileSync fs (.getPath this) (or (:encoding opts) "utf8"))))))) ;if no encoding, returns buffer . catch err?

(defn file-writer
  "For async and sync opts, this builds an appropriate write method given opts
  and attaches it to the reified file, returning the passed file.
    - opts: :encoding, :append, :async?, :stream?
    - if :stream? true, returns FileOutputStream as separate object.
    - if content is a Buffer instance, opt encoding is ignored
    - if :async? true, file.write() returns a chan which receives err|true on successful write.
  @param {IFile} file
  @param {IMap} opts a map of options
  @return {(IMap|IFn)}"
  [file opts]
  (if (:stream? opts)
    (file-stream-writer (make-output-stream file opts) opts)
    (if (:async? opts)
      (specify! file Object
        (write [this content]
          (let [filename (.getPath this)
                c (chan)
                cb (fn [err] (put! c (or err true)))]
            (.writeFile fs filename content
                        #js{"flag"     (or (:flags opts) (if (:append opts) "a" "w"))
                            "mode"     (or (:mode opts) 438)
                            "encoding" (or (:encoding opts) "utf8")}
                        cb)
            c)))
      (specify! file Object ;sync
        (write [this content]
          (let [filename (.getPath this)]
            (.writeFileSync fs filename content
                            #js{"flag"     (or (:flags opts) (if (:append opts) "a" "w"))
                                "mode"     (or (:mode opts)  438)
                                "encoding" (or (:encoding opts) "utf8")})))))))



(defn File*
  "@param {string} pathstr The abstract file path
   @return {IFile} a reified file"
  [pathstr]
  (let [pathstring  (atom pathstr)
        f (reify
            IFile
            IEquiv
            (-equiv [this that]
              (let [pathntype (juxt #(.-getPath %) type)]
                (= (pathntype this) (pathntype that))))
            Coercions
            (as-file [f] f)
            (as-url [f] (.to-url f))
            IOFactory
            (make-reader [^File this opts] (file-reader this opts))
            (make-writer [^File this opts] (file-writer this opts))
            (make-input-stream [^File this opts] (FileInputStream. this opts))
            (make-output-stream [^File this opts] (FileOutputStream. this  opts))
            IPrintWithWriter
            (-pr-writer [this writer opts] ;#object[java.io.File 0x751b0a12 "foo\\bar.txt"]
              (-write writer "#object [cljs-node-io.File")
              (-write writer (str "  "  (.getPath this)  " ]")))
            Object
            ; access tests do nothing when pass, throw when fail
            ; https://nodejs.org/api/fs.html#fs_fs_accesssync_path_mode
            (canExecute ^boolean [this]
              (if-not (= "win32" (.-platform js/process))
                (try
                  (if (nil? (.accessSync fs (.getPath this) (.-X_OK fs))) true)
                  (catch js/Error e false))
                (throw (js/Error "Testing if a file is executable has no effect on Windows "))))
            (canRead ^boolean [this]
              (try
                (if (nil? (.accessSync fs (.getPath this) (.-R_OK fs))) true)
                (catch js/Error e false)))
            (canWrite ^boolean [this]
              (try
                (if (nil? (.accessSync fs (.getPath this) (.-W_OK fs))) true)
                (catch js/Error e false)))
            (createNewFile ^boolean [this]
              (file-writer this {:flags "wx" :async? false})
              (try
                (do
                  (.write this)
                  true)
                (catch js/Error e false)))
            (delete ^boolean [this]
              (if (directory? @pathstring)
                (try
                  (do (.rmdirSync fs @pathstring) true)
                  (catch js/Error e false))
                (try
                   (do (.unlinkSync fs @pathstring) true)
                   (catch js/Error e false))))
            (deleteOnExit [this]
              (.on js/process "exit"  (fn [exit-code] (.delete this))))
            (equals ^boolean [this that] (= this that))
            (exists ^boolean [this] (.existsSync fs @pathstring)) ;deprecated buts stats docs are shit so im using it
            (getAbsoluteFile [this] (as-file (.getAbsolutePath this)))
            (getAbsolutePath [_] (.resolve path @pathstring))
            (getCanonicalFile [this] (as-file (.getCanonicalPath this)))
            (getCanonicalPath [_] (.normalize path @pathstring))
            (getName [_] (.-name (.parse path @pathstring)))
            (getParent [_] (.dirname path @pathstring))
            (getParentFile [this] (as-file (.getParent this))) ;=> File|nil
            (getPath ^string [this] (if (.isAbsolute this) (.getPath (Uri. @pathstring))  @pathstring))
            (hashCode ^int [_] (hash @pathstring))
            (isAbsolute ^boolean [_] (.isAbsolute path @pathstring))
            (isDirectory ^boolean [_] (directory? @pathstring))
            (isFile ^boolean [_] (file? @pathstring))
            (lastModified ^int [_]
              (let [stats (try (.statSync fs @pathstring) (catch js/Error e false))]
                (if stats
                  (.valueOf (.-mtime stats))
                  0)))
            (length ^int [_]
              (let [stats (try (.statSync fs @pathstring) (catch js/Error e false))]
                (if stats
                  (if (.isDirectory stats)
                    nil
                    (.-size stats))
                  0)))
            (list [_] ; ^ Vector|nil
              (if-not (directory? @pathstring)
                nil
                (try
                  (vec (.readdirSync fs @pathstring))
                  (catch js/Error e nil))))
            (list [this filterfn]
              (assert (= 2 (.-length filterfn)) "the file filterfn must accept 2 args, the dir File & name string")
              (if-let [files (.list this)]
                (filterv (partial filterfn @pathstring) files)))
            (listFiles [this]
              (.list this (fn [d name] (file? (str d (.-sep path)  name)))))
            (listFiles [this filterfn]
              (assert (= 2 (.-length filterfn)) "the file filterfn must accept 2 args, the dir File & name string")
              (if-let [files (.listFiles this)]
                (filterv (partial filterfn @pathstring) files)))
            (mkdir ^boolean [_]
              (try
                (do
                  (.mkdirSync fs @pathstring)
                  true)
                (catch js/Error e false)))
            (mkdirs ^boolean [this]
              (let [p  (.getPath this)
                    dirs (get-non-dirs p)]
                (try
                  (do
                    (doseq [d dirs]
                      (.mkdirSync fs d))
                    true)
                  (catch js/Error e false))))
            (renameTo ^boolean [this dest]
              (assert (string? dest) "destination must be a string")
              (try
                (do
                  (.renameSync fs @pathstring dest)
                  (reset! pathstring dest)
                  true)
                (catch js/Error e false)))
            (toString [_]  @pathstring)
            (toURI [f] (Uri. (str "file:" @pathstring))))]
(set! (.-constructor f) :File)
f))


(defn File
  "@return {IFile} a reified file"
  ([a]   (File*  (filepath a)))
  ([a b] (File*  (filepath a b))))

(defn createTempFile
  "@return {IFile} a reified file"
  ([prefix] (createTempFile prefix nil nil))
  ([prefix suffix] (createTempFile prefix suffix nil))
  ([prefix suffix dir]
    (let [tmpd (or dir (.tmpdir os))
          path (str tmpd (.-sep path) prefix (or suffix ".tmp"))
          f    (File. path)]
      f)))
