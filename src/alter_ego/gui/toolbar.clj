(ns alter-ego.gui.toolbar
  (:use [alter-ego.gui.util :only [add-action-listener image-icon]])
  (:use [alter-ego.gui.tree-actions])
  (:import (javax.swing SwingUtilities JPanel JButton)
	   (net.miginfocom.swing MigLayout)))

(defn button [i s f args]
  (doto (JButton.)
    (.setToolTipText s)
    (.putClientProperty "JButton.buttonType" "gradient")
    (.setIcon (image-icon i))
    (add-action-listener f args)))

(defn toolbar [tree]
  (let [toolbar (JPanel. (MigLayout. "insets 5 0 5 0"))]
    (doto toolbar
      (.add (button "new.png" "New" new-action tree))
      (.add (button "open.png" "Open" open-action tree))
      (.add (button "save.png" "Save" save-action tree))
      (.add (button "save-as.png" "Save As" 
		    save-as-action tree) "gapright 20")
      (.add (button "expand-tree.png" "Expand Tree" 
		    expand-tree-action tree) "gapright 20")
      (.add (button "up-arrow.png" "Move Node Up" move-up-action tree))
      (.add (button "down-arrow.png" "Move Node Down" 
		    move-down-action tree)))))

(comment 
  (defn frame []
    (let [toolbar (toolbar nil)] 
      (doto (javax.swing.JFrame. "Tree Editor")
	(.add toolbar)
	(.pack)
	(.setLocationRelativeTo nil)
	(.setVisible true))))

  (SwingUtilities/invokeLater frame)
  )
