package iterator;
import java.io.IOException;
import java.util.Comparator;

import global.TupleOrder;
import global.Vector100Dtype;
import heap.FieldNumberOutOfBoundException;
import heap.Tuple;

/**
 * Comparator for comparing two pnode objects based on the distance of their
 * associated tuples to a target vector.
 */
class DistanceComparator implements Comparator<pnode> {
  private int fld_no;
  private TupleOrder order;
  private Vector100Dtype target;

  public DistanceComparator(int fld_no, TupleOrder order, Vector100Dtype target) throws SortException {
    this.fld_no = fld_no;
    this.order = order;
    this.target = target;
  }

  /**
   * Calculates the distance from a tuple to the target vector.
   * @param tuple the tuple to calculate the distance for.
   * @return the calculated distance as a short value.
   * @throws SortException if there is an error in calculating the distance.
   */
  public short calculateDistance(Tuple tuple) throws SortException {
    try {
      Vector100Dtype vector100Dtype = tuple.get100DVectFld(fld_no);
      short dist = TupleUtils.getDistance(vector100Dtype, target);
      return dist;
    } catch (IOException | FieldNumberOutOfBoundException ex) {
      throw new RuntimeException(ex);
    }
}

  /**
   * Compares two pnode objects based on the distance of their tuples to a target vector.
   * @param node1 the first object to be compared.
   * @param node2 the second object to be compared.
   * @return
   */
  @Override
  public int compare(pnode node1, pnode node2) {
    try {
      short distance1 = calculateDistance(node1.tuple);
      short distance2 = calculateDistance(node2.tuple);

      if (distance1 < distance2) {
        return -1; // node1 is "smaller" (closer to target)
      } else if (distance1 > distance2) {
        return 1;  // node2 is "smaller"
      } else {
        return 0;  // distances are equal
      }
    } catch (SortException e) {
      // Handle exception appropriately, maybe throw RuntimeException if SortException should not be caught here
      e.printStackTrace();
      return 0; // Or throw RuntimeException to propagate error
    }
  }
}
