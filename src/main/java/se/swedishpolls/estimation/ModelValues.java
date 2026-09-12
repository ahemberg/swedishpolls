package se.swedishpolls.estimation;

import org.ejml.data.DMatrixRMaj;
import org.ejml.simple.SimpleEVD;
import org.ejml.simple.SimpleMatrix;

/** Immutable numerical values attached to an estimator input or fitted state. */
public final class ModelValues {
  private final SimpleMatrix matrix;

  private ModelValues(SimpleMatrix matrix) {
    this.matrix = matrix;
  }

  /** Copies caller-owned values into an immutable model value. */
  public static ModelValues copyOf(SimpleMatrix matrix) {
    return new ModelValues(matrix.copy());
  }

  /** Takes ownership of a newly computed matrix that has no other references. */
  static ModelValues owned(SimpleMatrix matrix) {
    return new ModelValues(matrix);
  }

  public int getNumRows() {
    return matrix.getNumRows();
  }

  public int getNumCols() {
    return matrix.getNumCols();
  }

  public double get(int index) {
    return matrix.get(index);
  }

  public double get(int row, int column) {
    return matrix.get(row, column);
  }

  public boolean hasUncountable() {
    return matrix.hasUncountable();
  }

  public double normF() {
    return matrix.normF();
  }

  public double elementMaxAbs() {
    return matrix.elementMaxAbs();
  }

  public SimpleMatrix copy() {
    return matrix.copy();
  }

  public SimpleMatrix transpose() {
    return matrix.transpose();
  }

  public SimpleMatrix scale(double value) {
    return matrix.scale(value);
  }

  public SimpleMatrix mult(SimpleMatrix right) {
    return matrix.mult(right);
  }

  public SimpleMatrix minus(SimpleMatrix right) {
    return matrix.minus(right);
  }

  public SimpleMatrix minus(ModelValues right) {
    return matrix.minus(right.matrix);
  }

  public SimpleMatrix elementExp() {
    return matrix.elementExp();
  }

  public SimpleMatrix extractMatrix(int y0, int y1, int x0, int x1) {
    return matrix.extractMatrix(y0, y1, x0, x1);
  }

  public SimpleEVD<SimpleMatrix> eig() {
    return matrix.eig();
  }

  public double[] toArray() {
    return matrix.getDDRM().getData().clone();
  }

  double symmetryError() {
    return matrix.minus(matrix.transpose()).elementMaxAbs();
  }

  DMatrixRMaj matrixCopy() {
    return matrix.getDDRM().copy();
  }
}
