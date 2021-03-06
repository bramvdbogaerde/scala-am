(define false #f)
(define true #t)

(define (create-stack eq-fnct)
  (let ((content '()))
    (define (empty?)
      (null? content))
    (define (push element)
      (set! content (cons element content))
      #t)
    (define (pop)
      (if (null? content)
          #f
          (let ((temp (car content)))
            (set! content (cdr content))
            temp)))
    (define (top)
      (if (null? content)
          #f
          (car content)))
    (define (is-in element)
      (if (member element content)
          #t
          #f))
    (define (dispatch m)
      (cond
        ((eq? m 'empty?) empty?)
        ((eq? m 'push) push)
        ((eq? m 'pop) pop)
        ((eq? m 'top) top)
        ((eq? m 'is-in) is-in)
        (else (error "unknown request -- create-stack" m))))
    dispatch))

    (let ((stack (create-stack =)))
      (and ((stack 'empty?))
           (begin
             ((stack 'push) 13)
             (not ((stack 'empty?))))
           ((stack 'is-in) 13)
           (= ((stack 'top)) 13)
           (begin ((stack 'push) 14)
                  (= ((stack 'pop)) 14))))
