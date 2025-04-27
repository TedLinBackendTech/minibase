/**
 * Class to encapsulate the query specification.
 * It contains fields for the query type, query field, target vector file name,
 * threshold (distance or number of neighbors), and output fields.
 */

public class QuerySpec {

  private QueryType queryType;
  private int queryField; // Field number for the 100D-vector attribute.
  private String targetFileName; // Name of the file containing the target vector.
  private int threshold; // For Range: the distance D; for NN: the number K.
  private boolean useIndex; // Whether to use index or not.
  private int[] outputFields; // Field numbers to output.
  private String filterOperator;
  private String filterValue;
  private int topK;
  private String indexOption;
  private QuerySpec nestedQuery;   // 子查询（Filter/NN）封装 QA1, T1, K1, I1, …
  private int       djoinField2;   // QA2
  private int       djoinDistance; // D2
  private String    djoinIndexOption; // I2
  public QuerySpec() {}
  public QueryType getQueryType() {
    return queryType;
  }

  public void setQueryType(QueryType queryType) {
    this.queryType = queryType;
  }

  public int getQueryField() {
    return queryField;
  }

  public void setQueryField(int queryField) {
    this.queryField = queryField;
  }

  public String getTargetFileName() {
    return targetFileName;
  }

  public void setTargetFileName(String targetFileName) {
    this.targetFileName = targetFileName;
  }

  public int getThreshold() {
    return threshold;
  }

  public void setThreshold(int threshold) {
    this.threshold = threshold;
  }

  public void setUseIndex(boolean useIndex) {
    this.useIndex = useIndex;
  }

  public boolean getUseIndex() {
    return useIndex;
  }

  public int[] getOutputFields() {
    return outputFields;
  }

  public void setOutputFields(int[] outputFields) {
    this.outputFields = outputFields;
  }
  public void setFilterOperator(String op) {
    this.filterOperator = op;
  }

  public String getFilterOperator() {
      return this.filterOperator;
  }

  public void setFilterValue(String value) {
      this.filterValue = value;
  }

  public String getFilterValue() {
      return this.filterValue;
  }

  public void setTopK(int k) {
      this.topK = k;
  }

  public int getTopK() {
      return this.topK;
  }
  public void setIndexOption(String indexOption) {
      this.indexOption = indexOption;
  }

  public String getIndexOption() {
      return indexOption;
  }

  public QuerySpec getNestedQuery()     { return nestedQuery; }
  public int       getDjoinField2()     { return djoinField2; }
  public int       getDjoinDistance()   { return djoinDistance; }
  public String    getDjoinIndexOption(){ return djoinIndexOption; }
  public void setNestedQuery(QuerySpec sub)     { this.nestedQuery = sub; }
  public void setDjoinField2(int f2)            { this.djoinField2 = f2; }
  public void setDjoinDistance(int d2)          { this.djoinDistance = d2; }
  public void setDjoinIndexOption(String opt2)  { this.djoinIndexOption = opt2; }



}

