import static dbmgr.DBOP.create_tuple;
import static dbmgr.DBOP.save_attrTypes;

import dbmgr.DBOP;
import diskmgr.PCounter;
import global.AttrType;
import global.RID;
import global.Vector100Dtype;
import heap.*;
import lshfindex.LSHFIndexFile;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class batchcreate {

 public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: batchcreate <DATAFILENAME> <RELNAME>");
            System.exit(1);
        }

        String dataFilename = args[0];
        String relName = args[1];

        try {
            // Initialize counters
            PCounter.initialize();

            // Open database
            DBOP.open_databaseDBNAME("mydb", 1000, 4000);
            DBOP.cleanup("mydb");

            // Read schema
            BufferedReader br = new BufferedReader(new FileReader(dataFilename));
            int numAttrs = Integer.parseInt(br.readLine().trim());
            String[] typeTokens = br.readLine().trim().split("\\s+");

            AttrType[] attrTypes = new AttrType[numAttrs];
            ArrayList<Integer> vectorAttrIdx = new ArrayList<>();

            for (int i = 0; i < numAttrs; i++) {
                int typeCode = Integer.parseInt(typeTokens[i]);
                switch (typeCode) {
                    case 1: attrTypes[i] = new AttrType(AttrType.attrInteger); break;
                    case 2: attrTypes[i] = new AttrType(AttrType.attrReal); break;
                    case 3: attrTypes[i] = new AttrType(AttrType.attrString); break;
                    case 4:
                        attrTypes[i] = new AttrType(AttrType.attrVector100D);
                        vectorAttrIdx.add(i + 1); // 注意：欄位從1開始
                        break;
                    default:
                        throw new IOException("Unknown attribute type code: " + typeCode);
                }
            }

            DBOP.save_attrTypes(relName, numAttrs, attrTypes);

            // Create heapfile
            Heapfile hf = new Heapfile(relName);

            // Create LSH Indexes
            LSHFIndexFile[] vectorIndexes = new LSHFIndexFile[vectorAttrIdx.size()];
            
            for (int i = 0; i < vectorAttrIdx.size(); i++) {
                String indexName = relName + "_Attr" + vectorAttrIdx.get(i) + "_index";
                // vectorIndexes[i] = new LSHFIndexFile(indexName);
                vectorIndexes[i] = new LSHFIndexFile(
                  indexName,
                  4, // h: 每層hash functions個數
                  2, // L: 層數
                  1, // nAttrs: attribute個數 (這邊通常是1)
                  new AttrType[]{ new AttrType(AttrType.attrVector100D) } // attrType array
              );
              
            }

            // Insert Tuples
            String line;
            boolean eof = false;
            while ((line = br.readLine()) != null) {
                String[] tupleValues = new String[numAttrs];
                tupleValues[0] = line;
                for (int i = 1; i < numAttrs; i++) {
                    String nextLine = br.readLine();
                    if (nextLine == null) {
                        eof = true;
                        System.err.println("End of file.");
                        break;
                    }
                    tupleValues[i] = nextLine;
                }
                if (eof) break;

                // Create tuple
                Tuple tuple = DBOP.create_tuple(numAttrs, attrTypes, tupleValues);
                byte[] tupleData = tuple.getTupleByteArray();
                RID rid = hf.insertRecord(tupleData);

                // 插入到每個vector index
                for (int i = 0; i < vectorAttrIdx.size(); i++) {
                    int fieldNo = vectorAttrIdx.get(i);
                    Vector100Dtype v = tuple.get100DVectFld(fieldNo);
                    vectorIndexes[i].insert(v, rid);
                }

                System.out.printf("Inserted tuple with RID<%d, %d>\n", rid.pageNo.pid, rid.slotNo);
            }

            br.close();
            // --- Close all LSHFIndexFile ---
            for (LSHFIndexFile index : vectorIndexes) {
              if (index != null) {
                  index.close();
              }
            }


            DBOP.close_database();

            System.out.println("Heapfile contains " + hf.getRecCnt() + " tuples");
            System.out.println("Page reads: " + PCounter.rcounter);
            System.out.println("Page writes: " + PCounter.wcounter);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
