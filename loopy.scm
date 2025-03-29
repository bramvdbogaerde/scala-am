(letrec
  ((loop (lambda (i) (if (= i 0) 0 (loop (- i 1))))))

  (loop 2))
