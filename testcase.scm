;; From Agha 1986, p. 52
(letrec ((stack-node (actor "stack-node" (content link)
                            (pop (customer)
                                 (if link
                                     (begin
                                       (send customer message content)
                                       (link))
                                     (begin
                                       (error "popping an empty stack")
                                       (terminate))))
                            (assertEmpty () (if link (error "not empty")
                                                     (become stack-node content link)))
                            (push (v)
                                  (become stack-node v (lambda () (become stack-node content link))))))
         (display-actor (actor "display" ()
                               (message (v) (display v) (become display-actor))))
         (disp (create display-actor)))
  (letrec ((loop (lambda (i)
                    (if (= i 2)
                      'done
                      (let
                        ((act (create stack-node #f #f)))
                          (send act push (int-top))
                          (send act pop disp)
                          (send act assertEmpty)
                          (loop (- i 1)))))))
    (loop 2)))
