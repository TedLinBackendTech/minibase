/**
 * Enum to represent the type of query being executed.
 * RANGE: Range query based on a distance threshold.
 * NN: Nearest Neighbor query to find K nearest neighbors.
 */
public enum QueryType {
  SORT,
  FILTER,
  RANGE,
  NN,
}
