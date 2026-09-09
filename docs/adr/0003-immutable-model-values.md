# Keep model values immutable at record boundaries

Estimator records hold Model values instead of exposing EJML's mutable `SimpleMatrix`. The wrapper takes ownership of newly computed matrices and exposes only non-mutating operations, so fitted states cannot change after construction and the estimator does not copy each matrix on the data path.

## Considered options

- Defensive copies in record constructors and accessors: rejected because each fitted day would allocate copies to defend against mutation no estimator caller needs.
- A SpotBugs rule exemption: rejected because the records represent values, and leaving their components mutable would make that contract false.
