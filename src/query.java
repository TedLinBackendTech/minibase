import bufmgr.BufMgr;
import diskmgr.PCounter;
import global.*;
import heap.Heapfile;
import heap.Tuple;
import iterator.*;
import lshfindex.*;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.io.*;
import java.util.Arrays;

public class query {

    public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: query RELNAME1 RELNAME2 QSNAME NUMBUF");
            System.exit(1);
        }
    
        String relName1 = args[0];
        String relName2 = args[1];
        String qsName = args[2];
        int numBuf = Integer.parseInt(args[3]);
    
        PCounter.initialize();
        BufMgr bufMgr = new BufMgr(numBuf, null);
    
        try {
            // 開DB
            SystemDefs sysdef = new SystemDefs("dbinstance/mydb", 1000, numBuf, "Clock");
    
            QuerySpec qs = parseQuerySpec(qsName);
    
            AttrType[] attrTypes = get_attrTypes(relName1, null);
            short[] Ssizes = new short[1];
            Ssizes[0] = 30;
    
            Heapfile hf = new Heapfile(relName1);
    
            if (qs.getQueryType() == QueryType.FILTER) {
                System.out.println("Running Filter Query");
    
                if (qs.getIndexOption().equalsIgnoreCase("Y")) {
                    // 用LSHF Index做篩選
                    System.out.println("Using LSHF index for FILTER query.");
    
                    String indexFileName = relName1 + "_Attr" + qs.getQueryField() + "_index";
                    LSHFIndexFile index = new LSHFIndexFile(indexFileName);
                    Vector100Dtype target = readTargetVector(qs.getTargetFileName());
    
                    LSHFFileScan scan = new LSHFFileScan(index, hf, target);
                    Tuple[] results = scan.LSHFFileRangeScan(null, qs.getThreshold(), attrTypes, qs.getQueryField());
                    if (results != null) {
                        for (Tuple t : results) {
                            t.print(attrTypes);
                        }
                    }
    
                } else {
                    // FileScan full scan
                    FldSpec[] projlist = new FldSpec[1];
                    projlist[0] = new FldSpec(new RelSpec(RelSpec.outer), 1);
    
                    CondExpr[] expr = new CondExpr[2];
                    expr[0] = new CondExpr();
                    expr[1] = null;
    
                    expr[0].op = new AttrOperator(AttrOperator.aopEQ);
                    expr[0].type1 = new AttrType(AttrType.attrSymbol);
                    expr[0].type2 = new AttrType(AttrType.attrInteger);
                    expr[0].operand1.symbol = new FldSpec(new RelSpec(RelSpec.outer), qs.getQueryField());
                    expr[0].operand2.integer = Integer.parseInt(qs.getFilterValue());
    
                    FileScan scan = new FileScan(relName1, attrTypes, Ssizes, (short) attrTypes.length,
                            1, projlist, expr);
    
                    Tuple t;
                    while ((t = scan.get_next()) != null) {
                        t.print(attrTypes);
                    }
                    scan.close();
                }
            } 
            else if (qs.getQueryType() == QueryType.NN) {
                System.out.println("Running NN Query");
    
                Vector100Dtype target = readTargetVector(qs.getTargetFileName());
    
                if (qs.getIndexOption().equalsIgnoreCase("Y")) {
                    System.out.println("Using LSHF index for NN query.");
    
                    String indexFileName = relName1 + "_Attr" + qs.getQueryField() + "_index";
                    LSHFIndexFile index = new LSHFIndexFile(indexFileName);
    
                    LSHFFileScan scan = new LSHFFileScan(index, hf, target);
    
                    Tuple[] results = scan.LSHFFileNNScan(null, qs.getThreshold(), attrTypes, qs.getQueryField());
                    if (results != null) {
                        for (Tuple t : results) {
                            t.print(attrTypes);
                        }
                    }
                } else {
                    System.out.println("Full scan for NN query.");
    
                    FileScan scan = new FileScan(relName1, attrTypes, Ssizes, (short) attrTypes.length,
                            1, new FldSpec[]{new FldSpec(new RelSpec(RelSpec.outer), qs.getQueryField())}, null);
    
                    PriorityQueue<TupleDistance> pq = new PriorityQueue<>();
    
                    Tuple t;
                    while ((t = scan.get_next()) != null) {
                        Vector100Dtype vec = t.get100DVectFld(qs.getQueryField());
                        double dist = getDistance(vec, target);
                        pq.add(new TupleDistance(t, dist));
                    }
    
                    scan.close();
    
                    System.out.println("Top " + qs.getTopK() + " nearest neighbors:");
                    for (int i = 0; i < qs.getTopK() && !pq.isEmpty(); i++) {
                        TupleDistance td = pq.poll();
                        td.tuple.print(attrTypes);
                        System.out.println("Distance: " + td.distance);
                    }
                }
            }
    
            System.out.println("Page reads: " + PCounter.rcounter);
            System.out.println("Page writes: " + PCounter.wcounter);
    
        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
// 幫你補上 get_attrTypes
private static AttrType[] get_attrTypes(String relName, AttrType[] attrTypes) throws IOException {
    BufferedReader schemaReader = new BufferedReader(new FileReader("schemas/" + relName + ".schema"));
    String line = schemaReader.readLine();
    if (line == null) { throw new IOException("Schema file is empty or corrupted."); }
    int numAttributes = Integer.parseInt(line.trim());
    attrTypes = new AttrType[numAttributes];

    line = schemaReader.readLine();
    if (line == null) { throw new IOException("Schema file is incomplete."); }
    String[] typeStrings = line.trim().split("\\s+");
    if (typeStrings.length != numAttributes) {
        throw new IOException("Schema file attribute type count mismatch.");
    }

    for (int i = 0; i < numAttributes; i++) {
        int typeCode = Integer.parseInt(typeStrings[i]);
        switch (typeCode) {
            case 1: attrTypes[i] = new AttrType(AttrType.attrInteger); break;
            case 2: attrTypes[i] = new AttrType(AttrType.attrReal); break;
            case 3: attrTypes[i] = new AttrType(AttrType.attrString); break;
            case 4: attrTypes[i] = new AttrType(AttrType.attrVector100D); break;
            default: throw new IOException("Unknown attribute type code in schema file: " + typeCode);
        }
    }
    schemaReader.close();
    return attrTypes;
}

// 幫你補上 readTargetVector
private static Vector100Dtype readTargetVector(String fileName) throws IOException {
    if (!fileName.endsWith(".txt"))
        fileName += ".txt";
    BufferedReader br = new BufferedReader(new FileReader("queries/" + fileName));
    String line = br.readLine().trim();
    br.close();
    String[] tokens = line.split("\\s+");
    if (tokens.length != 100) {
        throw new IllegalArgumentException("Target vector file must contain 100 integers.");
    }
    short[] vector = new short[100];
    for (int i = 0; i < 100; i++) {
        vector[i] = Short.parseShort(tokens[i]);
    }
    return new Vector100Dtype(vector);
}

  private static QuerySpec parseQuerySpec(String qsName) throws IOException {
    BufferedReader br = new BufferedReader(new FileReader(qsName));
    String line = br.readLine().trim();
    br.close();

    QuerySpec qs = new QuerySpec();

    if (line.startsWith("Range(")) {
      // (這段是你如果有支援Range再留，沒做Range可以略)
      throw new IllegalArgumentException("Range queries not supported in this project.");
    }
    else if (line.startsWith("NN(")) {
      qs.setQueryType(QueryType.NN);

      String inside = line.substring("NN(".length(), line.length() - 1);
      String[] tokens = inside.split(",");

      for (int i = 0; i < tokens.length; i++) {
        tokens[i] = tokens[i].trim();
      }

      qs.setQueryField(Integer.parseInt(tokens[0])); // QA
      qs.setTargetFileName(tokens[1]);               // T
      qs.setThreshold(Integer.parseInt(tokens[2]));   // K

      qs.setIndexOption(tokens[3]);                  // I  <-- 這行是新加的！

      int numOut = tokens.length - 4;
      int[] outFields = new int[numOut];
      for (int i = 0; i < numOut; i++) {
        outFields[i] = Integer.parseInt(tokens[i + 4]);
      }
      qs.setOutputFields(outFields);
    }
    else if (line.startsWith("Filter(")) {
      qs.setQueryType(QueryType.FILTER);

      String inside = line.substring("Filter(".length(), line.length() - 1);
      String[] tokens = inside.split(",");

      for (int i = 0; i < tokens.length; i++) {
        tokens[i] = tokens[i].trim();
      }

      qs.setQueryField(Integer.parseInt(tokens[0])); // QA
      qs.setTargetFileName(tokens[1]);               // T
      qs.setThreshold(Integer.parseInt(tokens[2]));   // K

      qs.setIndexOption(tokens[3]);                  // I <-- 加這行（Filter也支援I）

      int numOut = tokens.length - 4;
      int[] outFields = new int[numOut];
      for (int i = 0; i < numOut; i++) {
        outFields[i] = Integer.parseInt(tokens[i + 4]);
      }
      qs.setOutputFields(outFields);
    }
    else {
      throw new IllegalArgumentException("Unknown query type: " + line);
    }

    return qs;
}

  private static double getDistance(Vector100Dtype v1, Vector100Dtype v2) {
    double sum = 0.0;
    short[] vec1 = v1.getDimension();
    short[] vec2 = v2.getDimension();

    for (int i = 0; i < 100; i++) {
        double diff = vec1[i] - vec2[i];
        sum += diff * diff;
    }
    return Math.sqrt(sum);
  }



}
class TupleDistance implements Comparable<TupleDistance> {
  Tuple tuple;
  double distance;

  TupleDistance(Tuple t, double d) {
      this.tuple = t;
      this.distance = d;
  }

  @Override
  public int compareTo(TupleDistance o) {
      return Double.compare(this.distance, o.distance);
  }
}
