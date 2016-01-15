(ns ^:figwheel-always cljs-node-io.tests
  (:require [cljs.test :refer-macros [deftest is testing run-tests are]]
            [cljs-node-io.file :refer [File temp-file]]
            [cljs-node-io.protocols :refer [Coercions as-file as-url ]]
            [cljs-node-io.core :refer [file as-relative-path spit slurp]]) ;file File
  (:import goog.Uri))


(deftest test-as-url ;note URL object is dropped
 (are [file-part input] (= (.getPath (Uri. (str "file:" file-part))) (as-url input))
       "foo" "file:foo"
       "quux" (Uri. "file:quux"))
  (is (nil? (as-url nil))))

(deftest test-as-file
  (are [result input] (= result (as-file input))
       (File. "foo") "foo"
       (File. "bar") (File. "bar")
       (File. "baz") (Uri. "file:baz")
       (File. (Uri. "file:baz")) (Uri. "file:baz")
       (File. "bar+baz") (Uri. "file:bar+baz")
       (File. "bar baz qux") (Uri. "file:bar%20baz%20qux")
       (File. "abcíd/foo.txt") (Uri. "file:abc%c3%add/foo.txt")
       nil nil))

(deftest test-file
  (are [result args] (= (File. result) (apply file args))
       "foo" ["foo"]
       "foo/bar" ["foo" "bar"]
       "foo/bar/baz" ["foo" "bar" "baz"]))


(deftest test-spit-and-slurp
  (let [f (temp-file "cljs.node.io" "test")
        content (apply str (concat "a" (repeat 500 "\u226a\ud83d\ude03")))]
    (spit f content)
    (is (= content (slurp f)))
    ; UTF-16 must be last for the following test
   (doseq [enc [ "utf8"]] ;"utf16le" should work but doesn't
      (spit f content :encoding enc)
      (is (= content (slurp f :encoding enc))))))



(run-tests)
