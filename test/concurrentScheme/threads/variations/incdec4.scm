(letrec ((counter 0)
         (lock (new-lock))
         (inc (lambda ()
                (acquire lock)
                (set! counter (+ counter 1))
                (release lock)))
         (dec (lambda ()
                (acquire lock)
                (set! counter (- counter 1))
                (release lock)))
         (t1 (fork (inc)))
         (t2 (fork (dec)))
         (t3 (fork (inc)))
         (t4 (fork (dec))))
  (join t1)
  (join t2)
  (join t3)
  (join t4))
