(define NumSimulations (int-top))
(define NumChannels (int-top))
(define NumColumns (int-top))
(define SinkPrintRate (int-top))
(define H (int-top))
(define F (int-top))
(define MaxValue 1000)
(define (build-vector n f)
  (letrec ((v (make-vector n #f))
           (loop (lambda (i)
                   (if (< i n)
                       (begin
                         (vector-set! v i (f i))
                         (loop (+ i 1)))
                       v))))
    (loop 0)))
(define (vector-foreach f v)
  (letrec ((loop (lambda (i)
                   (if (< i (vector-length v))
                       (begin
                         (f (vector-ref v i))
                         (loop (+ i 1)))
                       'done))))
    (loop 0)))
(define foldl
  (lambda (f base lst)
    (define foldl-aux
      (lambda (base lst)
        (if (null? lst)
            base
            (foldl-aux (f base (car lst)) (cdr lst)))))
    (foldl-aux base lst)))
(define producer
  (a/actor "producer" (messages-sent)
           (next (source)
                 (if (= messages-sent NumSimulations)
                     (begin
                       (a/send source exit)
                       (a/terminate))
                     (begin
                       (a/send source boot)
                       (a/become producer (+ messages-sent 1)))))))
(define source
  (a/actor "source" (next producer current)
           (boot ()
                 (a/send next value current)
                 (a/send producer next self)
                 (a/become source next producer (modulo (+ current 1) MaxValue)))
           (exit ()
                 (a/terminate))))
(define branches
  (a/actor "branches" (next banks)
           (value (v)
                  (vector-foreach (lambda (a) (a/send a value v)) banks)
                  (a/become branches next banks))
           (exit ()
                 (vector-foreach (lambda (a) (a/send a exit)) banks)
                 (a/terminate))))
(define tagged-forward
  (a/actor "tagged-forward" (source-id next)
           (value (v)
                  (a/send next sourced-value source-id v)
                  (a/become tagged-forward source-id next))
           (exit () (a/terminate))))
(define fir-filter
  (a/actor "fir-filter" (source-id next data data-index data-full)
           (value (v)
                  (vector-set! data data-index v)
                  (if (or (= (+ data-index 1) NumColumns) data-full)
                      (letrec ((loop (lambda (i sum)
                                       (if (= i NumColumns)
                                           sum
                                           (loop (+ i 1) (+ sum (* (vector-ref data i) (int-top))))))))
                        (a/send next value (loop 0 0))
                        (a/become fir-filter source-id next data 0 #t))
                      (a/become fir-filter source-id next data (+ data-index 1) data-full)))
           (exit () (a/terminate))))
(define delay
  (a/actor "delay" (source-id delay-length next state place-holder)
           (value (v)
                  (a/send next value (vector-ref state place-holder))
                  (vector-set! state place-holder v)
                  (a/become delay source-id delay-length next state (modulo (+ place-holder 1) delay-length)))
           (exit () (a/terminate))))
(define sample-filter
  (a/actor "sample-filter" (sample-rate next samples-received)
           (value (v)
                  (if (= samples-received 0)
                      (a/send next value v)
                      (a/send next value 0))
                  (a/become sample-filter sample-rate next (modulo (+ samples-received 1) sample-rate)))
           (exit () (a/terminate))))

(define (create-bank source-id integrator)
  (let* ((tf (a/create tagged-forward source-id integrator))
         (ff (a/create fir-filter (string-append (number->string source-id) ".2") tf (make-vector NumColumns 0) 0 #f))
         (d (a/create delay (string-append (number->string source-id) ".2") (- NumColumns 1) ff (make-vector (- NumColumns 1) 0) 0))
         (sf (a/create sample-filter NumColumns d 0))
         (ff2 (a/create fir-filter (string-append (number->string source-id) ".1") sf (make-vector NumColumns 0) 0 #f))
         (d2 (a/create delay (string-append (number->string source-id) ".1") (- NumColumns 1) ff2 (make-vector (- NumColumns 1) 0) 0))
         (first-actor d2))
    (a/create bank source-id integrator first-actor)))
(define bank
  (a/actor "bank" (source-id integrator first-actor)
           (value (v)
                  (a/send first-actor value v)
                  (a/become bank source-id integrator first-actor))
           (exit () (a/terminate))))
(define integrator
  (a/actor "integrator" (next data exits-received)
           (sourced-value (source-id value)
                          ;; Some complex processing not modeled here
                          ;; TODO: the list has to be copied when sent, instead we compute the sum here
                          (letrec ((make-values (lambda (i acc) (if (= i NumChannels) acc (make-values (+ i 1) (cons (int-top) acc))))))
                            (if (bool-top)
                                (a/send next collection (foldl (lambda (v acc) (+ v acc)) 0 (make-values 0 '()))))
                            (a/become integrator next data exits-received)))
           (exit ()
                 (if (= (+ exits-received 1) NumChannels)
                     (a/terminate)
                     (a/become integrator next data (+ exits-received 1))))))
(define combine
  (a/actor "combine" (next)
           (collection (sum)
                       (a/send next value sum)
                       (a/become combine next))
           (exit () (a/terminate))))
(define sink
  (a/actor "sink" (print-rate count)
           (value (v)
                  (a/become sink print-rate (modulo (+ count 1) print-rate)))
           (exit () (a/terminate))))


(define producer-actor (a/create producer 0))
(define sink-actor (a/create sink SinkPrintRate 0))
(define combine-actor (a/create combine sink-actor))
(define integrator-actor (a/create integrator combine-actor '() 0))
(define banks (build-vector NumChannels (lambda (i)
                                          (create-bank i integrator-actor))))
(define branches-actor (a/create branches integrator-actor banks))
(define source-actor (a/create source branches-actor producer-actor 0))
(a/send producer-actor next source-actor)
