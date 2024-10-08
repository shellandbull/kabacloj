(ns index
  (:require [tablecloth.api :as tc]
            [tablecloth.column.api :as tcc]
            [scicloj.noj.v1.vis.hanami :as hanami]
            [aerial.hanami.templates :as ht]
            [tech.v3.dataset.categorical :as ds-cat]
            [tech.v3.dataset.modelling :as ds-mod]
            [tech.v3.dataset.column-filters :as cf]
            [scicloj.kindly.v4.kind :as kind]
            [scicloj.metamorph.core :as morph]
            [tablecloth.api.split :as split]
            [scicloj.metamorph.ml :as ml]
            [tech.v3.dataset :as tds]
            [camel-snake-kebab.core :as csk]
            [scicloj.metamorph.ml.loss :as loss]
            [scicloj.ml.smile.classification]))


;; #### Fetching data

(def ds
  (-> "data/index.csv"
      (tc/dataset {:key-fn keyword})))

(tc/unique-by ds :coffee_name)

;; #### Feature engineering

(defn coffee-has-milk [str]
  (boolean (some #(= str %) ["Latte" "American With Milk" "Cortado" "Cappuccino"])))

(defn coffee-has-chocolate [str]
  (boolean (some #(= str %) ["Cocoa" "Hot Chocolate"])))

(def coffee-names
  (distinct (apply list (:coffee_name ds))))

coffee-names

(def with-features
  (-> ds
      (tc/add-column :with-milk
                     (fn [dataset]
                       (map
                        (fn [name] (coffee-has-milk name))
                        (:coffee_name ds))))
      (tc/add-column :with-chocolate
                     (fn [dataset]
                       (map
                        (fn [name] (coffee-has-chocolate name))
                        (:coffee_name ds))))))


(def categorical-feature-columns [:coffee_name :with-milk :with-chocolate])
(def target-column :cash_type)

(map
 #(hash-map
   :col-name %
   :values  (distinct (get with-features %)))
 categorical-feature-columns)


(tc/column-names with-features)
(tds/descriptive-stats with-features)

(-> with-features
    (:with-milk)
    frequencies)


;; #### Preparing the features to be fit into a model


(def relevant-with-features-ds
  (-> with-features
      (tc/select-columns (conj categorical-feature-columns target-column))
      (tds/drop-missing)
      (tds/categorical->number [target-column] ["card" "cash"] :float64)
      (ds-mod/set-inference-target target-column)))

(def cat-maps
  [
   (ds-cat/fit-categorical-map relevant-with-features-ds :coffee_name coffee-names :float64)
   (ds-cat/fit-categorical-map relevant-with-features-ds :with-milk [true false] :float64)
   (ds-cat/fit-categorical-map relevant-with-features-ds :with-chocolate [true false] :float64)
   ])

cat-maps

(def numeric-ds
  (reduce (fn [ds cat-map]
            (ds-cat/transform-categorical-map ds cat-map))
          relevant-with-features-ds
          cat-maps))

(tc/head numeric-ds 10)


;; #### Mounting the model

(def split
  (first
   (tc/split->seq numeric-ds :holdout {:seed 112723})))

split

;; https://scicloj.github.io/noj/noj_book.ml_basic.html#train-a-model

(def model-with-logistic-regression
  (ml/train (:train split) {:model-type :smile.classification/logistic-regression}))

model-with-logistic-regression

(def prediction-with-logistic-regression
  (ml/predict (:test split) model-with-logistic-regression))

;; #### Measuring model accuracy

;; Accuracy of a logistic regression

(loss/classification-accuracy
 (:cash_type (ds-cat/reverse-map-categorical-xforms (:test split)))
 (:cash_type (ds-cat/reverse-map-categorical-xforms prediction-with-logistic-regression)))

(def rf-model
  (ml/train (:train split) {:model-type :smile.classification/random-forest}))

(def rf-prediction
  (ml/predict (:test split) rf-model))

;; Accuracy of a random forest model

(loss/classification-accuracy
 (:cash_type (ds-cat/reverse-map-categorical-xforms (:test split)))
 (:cash_type (ds-cat/reverse-map-categorical-xforms rf-prediction)))

(->>
 (map
  (fn [predictor importance]
    (hash-map :predictor (-> predictor str csk/->kebab-case-keyword)
              :importance importance))
  (-> rf-model ml/thaw-model .formula .predictors)
  (-> rf-model ml/thaw-model .importance))
 (sort-by :importance)
 reverse)

;; We conclude that although a model with ~90% accuracy was built. Not a single of it's features holds
;; a relevant relationship to the target.

;;  This means that it's hard to guess if you will pay cash or card if we know what type of coffee you're getting. And wether or not it has milk or chocolate in it
