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
        String qsName    = args[2];
        int    numBuf    = Integer.parseInt(args[3]);
    
        PCounter.initialize();
        // new BufMgr(numBuf, 0);  // 初始化 Buffer Manager
        BufMgr bufMgr = new BufMgr(numBuf, null);
        try {
            // 打开或创建数据库
            SystemDefs sysdef;
            File dbfile = new File("../dbinstance/mydb");
            if (!dbfile.exists()) {
                System.out.println("mydb not found. Creating a new database...");
                sysdef = new SystemDefs("../dbinstance/mydb", 1000, 4000, "Clock");
            } else {
                System.out.println("mydb found. Opening existing database...");
                sysdef = new SystemDefs("../dbinstance/mydb", 1000, 4000, "Clock");
            }

            // 解析查询规范
            QuerySpec qs = parseQuerySpec(qsName);

            // 根据类型分支
            if (qs.getQueryType() == QueryType.FILTER) {
                runFilter(relName1, qs);
            }
            else if (qs.getQueryType() == QueryType.NN) {
                runNN(relName1, qs);
            }
            else if (qs.getQueryType() == QueryType.DJOIN) {
                runDJoin(relName1, relName2, qs, numBuf);
            } else {
                throw new IllegalArgumentException("Unsupported query type: " + qs.getQueryType());
            }

            System.out.println("Page reads:  " + PCounter.rcounter);
            System.out.println("Page writes: " + PCounter.wcounter);

        } catch (Exception e) {
            System.err.println("Error executing query: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ------------------ FILTER ------------------
    private static void runFilter(String relName, QuerySpec qs) throws Exception {
        System.out.println("Running Filter Query");
        AttrType[] in = get_attrTypes(relName);
        short[] Ssizes = new short[1];
        Ssizes[0] = 30;
        Heapfile hf = new Heapfile(relName);

        if (qs.getIndexOption().equalsIgnoreCase("Y")) {
            System.out.println("Using LSHF index for FILTER query.");
            String idxName = relName + "_Attr" + qs.getQueryField() + "_index";
            LSHFIndexFile idx = new LSHFIndexFile(idxName);
            Vector100Dtype target = readTargetVector(qs.getTargetFileName());
            LSHFFileScan scan = new LSHFFileScan(idx, hf, target);
            Tuple[] results = scan.LSHFFileRangeScan(null, qs.getThreshold(), in, qs.getQueryField());
            if (results != null) {
                for (Tuple t : results) t.print(in);
            }
        } else {
            // 全表扫描
            FldSpec[] projlist = { new FldSpec(new RelSpec(RelSpec.outer), 1) };
            CondExpr[] expr = { new CondExpr(), null };
            expr[0].op        = new AttrOperator(AttrOperator.aopEQ);
            expr[0].type1     = new AttrType(AttrType.attrSymbol);
            expr[0].operand1.symbol = new FldSpec(new RelSpec(RelSpec.outer), qs.getQueryField());
            // 根据字段类型动态解析
            AttrType ft = in[qs.getQueryField()-1];
            switch (ft.attrType) {
                case AttrType.attrInteger:
                    expr[0].type2 = new AttrType(AttrType.attrInteger);
                    expr[0].operand2.integer = Integer.parseInt(qs.getFilterValue());
                    break;
                case AttrType.attrReal:
                    expr[0].type2 = new AttrType(AttrType.attrReal);
                    expr[0].operand2.real = Float.parseFloat(qs.getFilterValue());
                    break;
                case AttrType.attrString:
                    expr[0].type2 = new AttrType(AttrType.attrString);
                    expr[0].operand2.string = qs.getFilterValue();
                    break;
                default:
                    throw new IllegalArgumentException("Cannot filter on type: " + ft.attrType);
            }
            FileScan scan = new FileScan(relName, in, Ssizes, (short)in.length, 1, projlist, expr);
            Tuple t;
            while ((t = scan.get_next()) != null) t.print(in);
            scan.close();
        }
    }

    // ------------------ NN ------------------
    private static void runNN(String relName, QuerySpec qs) throws Exception {
        System.out.println("Running NN Query");
        AttrType[] in = get_attrTypes(relName);
        short[] Ssizes = new short[1];
        Ssizes[0] = 30;
        Heapfile hf = new Heapfile(relName);
        Vector100Dtype target = readTargetVector(qs.getTargetFileName());

        if (qs.getIndexOption().equalsIgnoreCase("Y")) {
            System.out.println("Using LSHF index for NN query.");
            String idxName = relName + "_Attr" + qs.getQueryField() + "_index";
            LSHFIndexFile idx = new LSHFIndexFile(idxName);
            LSHFFileScan scan = new LSHFFileScan(idx, hf, target);
            Tuple[] results = scan.LSHFFileNNScan(null, qs.getThreshold(), in, qs.getQueryField());
            if (results != null) {
                for (Tuple t : results) t.print(in);
            }
        } else {
            System.out.println("Full scan for NN query.");
            FileScan scan = new FileScan(
                relName, in, Ssizes, (short)in.length,
                1, new FldSpec[]{ new FldSpec(new RelSpec(RelSpec.outer), qs.getQueryField()) },
                null
            );
            PriorityQueue<TupleDistance> pq = new PriorityQueue<>();
            Tuple t;
            while ((t = scan.get_next()) != null) {
                Vector100Dtype v = t.get100DVectFld(qs.getQueryField());
                double d = getDistance(v, target);
                pq.add(new TupleDistance(t,d));
            }
            scan.close();
            System.out.println("Top " + qs.getTopK() + " nearest neighbors:");
            for (int i = 0; i < qs.getTopK() && !pq.isEmpty(); i++) {
                TupleDistance td = pq.poll();
                td.tuple.print(in);
                System.out.println("Distance: " + td.distance);
            }
        }
    }

    // ------------------ DJOIN ------------------
    private static void runDJoin(String relName1, String relName2, QuerySpec qs, int numBuf) throws Exception {
        System.out.println("Running Distance Join Query");

        // 1) 读取 schema
        AttrType[] in1 = get_attrTypes(relName1);
        AttrType[] in2 = get_attrTypes(relName2);
        short[] s1 = new short[in1.length], s2 = new short[in2.length];
        Arrays.fill(s1,(short)30); Arrays.fill(s2,(short)30);

        // 2) outer 全表扫描
        FldSpec[] outerProj = new FldSpec[in1.length];
        for (int i = 0; i < in1.length; i++) {
            // outer 表的第 i+1 列
            outerProj[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1);
        }

        FileScan outer = new FileScan(
            relName1,
            in1,    // schema
            s1,     // strSizes
            (short) in1.length,   // tuple header 中的字段数
            outerProj.length,     // 输出多少列
            outerProj,            // 投影列表
            null                  // 不做预过滤
        );


        // 3) 构造 rightFilter：距離 <= D2
        CondExpr[] rightFilter = { new CondExpr(), null };
        rightFilter[0].op        = new AttrOperator(AttrOperator.aopLE);
        rightFilter[0].type1     = new AttrType(AttrType.attrSymbol);
        rightFilter[0].type2     = new AttrType(AttrType.attrSymbol);
        rightFilter[0].operand1.symbol = new FldSpec(new RelSpec(RelSpec.outer),
                                                     qs.getNestedQuery().getQueryField());
        rightFilter[0].operand2.symbol = new FldSpec(new RelSpec(RelSpec.innerRel),
                                                     qs.getDjoinField2());
        rightFilter[0].distance = qs.getDjoinDistance();

        // 4) 构造投影列表
        int[] of = qs.getOutputFields();
        FldSpec[] projList = new FldSpec[of.length];
        for (int i = 0; i < of.length; i++) {
            if (of[i] <= in1.length)
                projList[i] = new FldSpec(new RelSpec(RelSpec.outer), of[i]);
            else
                projList[i] = new FldSpec(new RelSpec(RelSpec.innerRel), of[i] - in1.length);
        }

            // 5) 索引类型和名称（对 inner 表用 index）  
        //    但如果 I2="N"，我们后面会走简单 NestedLoopsJoins，不会打开任何索引文件。
        String idxOption2 = qs.getDjoinIndexOption();
        IndexType idxType = (idxOption2.equalsIgnoreCase("Y") 
                            ? new IndexType(IndexType.LSHFIndex) 
                            : new IndexType(IndexType.B_Index));
        String idxName = relName2 + "_Attr" + qs.getDjoinField2() + "_index";
        String innerRelName = relName2;
        // 6) 根据 I2 决定用哪种 Join 算子
        Iterator joinOp;
        if (idxOption2.equalsIgnoreCase("Y")) {
        // 用 Index‐Nested‐Loop Join（支持向量索引或 BTree）
        joinOp = new INLJoins(
            in1, in1.length, s1,
            in2, in2.length, s2,
            numBuf,
            outer,
            relName2,
            idxType,
            idxName,
            null,          // outer 上的额外 outFilter
            rightFilter,
            projList,
            projList.length
        );
        } else {
        // 不用索引，直接普通 Nested‐Loops Join
        joinOp = new NestedLoopsJoins(
            in1, in1.length,  s1,
            in2, in2.length,  s2,
            numBuf,           // amt_of_mem
            outer,            // FileScan outer
            innerRelName,     // name of the heapfile for inner table
            null,             // outFilter（额外过滤，这里不需要）
            rightFilter,      // join 条件：distance <= D2
            projList,
            projList.length
        );
        }

        // 7) 拉结果并打印
        Tuple t;
        AttrType[] resultTypes = new AttrType[projList.length];
        for (int i = 0; i < projList.length; i++) {
        if (projList[i].relation.key == RelSpec.outer)
            resultTypes[i] = in1[projList[i].offset - 1];
        else
            resultTypes[i] = in2[projList[i].offset - 1];
        }
        while ((t = joinOp.get_next()) != null) {
        t.print(resultTypes);
        }
        joinOp.close();
    }

    // --------- 辅助方法 ---------
    private static AttrType[] get_attrTypes(String relName) throws IOException {
        BufferedReader r = new BufferedReader(new FileReader("schemas/" + relName + ".schema"));
        int n = Integer.parseInt(r.readLine().trim());
        AttrType[] a = new AttrType[n];
        String[] toks = r.readLine().trim().split("\\s+");
        for (int i = 0; i < n; i++) {
            switch (Integer.parseInt(toks[i])) {
                case 1: a[i] = new AttrType(AttrType.attrInteger); break;
                case 2: a[i] = new AttrType(AttrType.attrReal);    break;
                case 3: a[i] = new AttrType(AttrType.attrString);  break;
                case 4: a[i] = new AttrType(AttrType.attrVector100D); break;
                default: throw new IOException("Bad schema");
            }
        }
        r.close();
        return a;
    }

    private static Vector100Dtype readTargetVector(String fn) throws IOException {
        if (!fn.endsWith(".txt")) fn += ".txt";
        BufferedReader r = new BufferedReader(
            new FileReader("../src/phase3_demo_data/" + fn));
        String line = r.readLine().trim();
        r.close();
        String[] toks = line.split("\\s+");
        if (toks.length != 100) throw new IOException("Bad vector file");
        short[] v = new short[100];
        for (int i = 0; i < 100; i++) v[i] = Short.parseShort(toks[i]);
        return new Vector100Dtype(v);
    }

    // 从文件解析 QuerySpec
    private static QuerySpec parseQuerySpec(String path) throws IOException {
        BufferedReader r = new BufferedReader(new FileReader(path));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line.trim());
        r.close();
        return parseQuerySpecFromString(sb.toString());
    }

    // 从字符串解析 QuerySpec（支持 FILTER/NN/DJOIN）
    private static QuerySpec parseQuerySpecFromString(String query) throws IOException {
        QuerySpec qs = new QuerySpec();
        if (query.startsWith("Filter(")) {
            qs.setQueryType(QueryType.FILTER);
            String inside = query.substring(7, query.length()-1);
            String[] parts = inside.split(",");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            qs.setQueryField(Integer.parseInt(parts[0]));
            qs.setFilterValue(parts[1]);
            qs.setThreshold(Integer.parseInt(parts[2]));
            qs.setIndexOption(parts[3]);
            int m = parts.length - 4;
            int[] of = new int[m];
            for (int i = 0; i < m; i++) of[i] = Integer.parseInt(parts[4+i]);
            qs.setOutputFields(of);
        }
        else if (query.startsWith("NN(")) {
            qs.setQueryType(QueryType.NN);
            String inside = query.substring(3, query.length()-1);
            String[] parts = inside.split(",");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            qs.setQueryField(Integer.parseInt(parts[0]));
            qs.setTargetFileName(parts[1]);
            qs.setThreshold(Integer.parseInt(parts[2]));
            qs.setIndexOption(parts[3]);
            int m = parts.length - 4;
            int[] of = new int[m];
            for (int i = 0; i < m; i++) of[i] = Integer.parseInt(parts[4+i]);
            qs.setOutputFields(of);
            qs.setTopK(qs.getThreshold());
        }
        else if (query.startsWith("DJOIN(")) {
            String inside = query.substring(6, query.length()-1).trim();
            // 找子查询 NN(...) 或 Filter(...)
            int depth=0, cut=-1;
            for (int i=0; i<inside.length(); i++){
                char c = inside.charAt(i);
                if(c=='(') depth++;
                else if(c==')' && (--depth)==0){ cut=i; break; }
            }
            if(cut<0) throw new IOException("Bad DJOIN");
            String sub = inside.substring(0,cut+1);
            String rest = inside.substring(cut+2).trim(); // 跳过 '),'
            String[] parts = rest.split(",");
            for(int i=0;i<parts.length;i++) parts[i]=parts[i].trim();

            QuerySpec subQ = parseQuerySpecFromString(sub);
            int qa2 = Integer.parseInt(parts[0]);
            int d2  = Integer.parseInt(parts[1]);
            String i2 = parts[2];
            int m = parts.length - 3;
            int[] of = new int[m];
            for(int i=0;i<m;i++) of[i] = Integer.parseInt(parts[3+i]);

            qs.setQueryType(QueryType.DJOIN);
            qs.setNestedQuery(subQ);
            qs.setDjoinField2(qa2);
            qs.setDjoinDistance(d2);
            qs.setDjoinIndexOption(i2);
            qs.setOutputFields(of);
        }
        else {
            throw new IOException("Unknown query: " + query);
        }
        return qs;
    }

    // Euclidean distance 辅助
    private static double getDistance(Vector100Dtype v1, Vector100Dtype v2) {
        double sum=0;
        short[] a=v1.getDimension(), b=v2.getDimension();
        for(int i=0;i<100;i++){
            double d = a[i]-b[i];
            sum += d*d;
        }
        return Math.sqrt(sum);
    }

}

// 辅助类，按距离排序
class TupleDistance implements Comparable<TupleDistance> {
    Tuple tuple;
    double distance;
    TupleDistance(Tuple t, double d){ tuple=t; distance=d; }
    public int compareTo(TupleDistance o){
        return Double.compare(this.distance,o.distance);
    }
}