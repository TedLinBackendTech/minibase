package global;

/**  Vector100Dtype.java
 *  This defines a class for a 100-dimensional vector with short integer values.
 */
public class Vector100Dtype {

  private final static short MIN_VAL = -10000;
  private final static short MAX_VAL = 10000;
  private short[] dimension;

  public Vector100Dtype() {
    dimension = new short[100];
  }

  public Vector100Dtype(short[] dimension) {
    this.dimension = new short[100];
    setDimension(dimension);
  }
  public short[] getDimension() {
    return dimension;
  }
  public void setDimension(short[] dimension) {
    for (int i = 0; i < 100; i++) {
      if (dimension[i] > MAX_VAL)
        this.dimension[i] = MAX_VAL;
      else if (dimension[i] < MIN_VAL)
        this.dimension[i] = MIN_VAL;
      else
        this.dimension[i] = dimension[i];
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    Vector100Dtype that = (Vector100Dtype) obj;
    for (int i = 0; i < 100; i++) {
      if (this.dimension[i] != that.dimension[i]) {
        return false;
      }
    }
    return true;
  }
}
