import static dbmgr.DBOP.create_tuple;
import static dbmgr.DBOP.save_attrTypes;

import dbmgr.DBOP;
import diskmgr.PCounter;
import global.AttrType;
import global.PageId;
import global.RID;
import global.SystemDefs;
import global.Vector100Dtype;
import heap.*;
import lshfindex.LSHFIndexFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class batchcreate {

 public static void main(String[] args) {
        if (args.length != 4) {
            System.err.println("Usage: batchcreate <DATAFILENAME> <RELNAME> <L> <h>");
            System.exit(1);
        }

        String dataFilename = args[0];
        String relName = args[1];
        int L = Integer.parseInt(args[2]);
        int h = Integer.parseInt(args[3]);

        try {
            // Initialize counters
            PCounter.initialize();
            File dbfile = new File("../dbinstance/mydb");
            // File dbfile = new File("../dbinstance/mydb");
            if (!dbfile.exists()) {
                System.out.println("mydb not found. Creating a new database...");
                new SystemDefs("../dbinstance/mydb", 1000, 4000, "Clock");
            } else {
                System.out.println("mydb found. Opening existing database...");
                new SystemDefs("../dbinstance/mydb", 1000, 4000, "Clock");
            }
            
            // Open database
            // DBOP.open_databaseDBNAME("mydb", 1000, 4000);
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

            // --- Cleanup old heapfile and indexes if exist ---
            DBOP.cleanup(relName);

            for (int i = 0; i < vectorAttrIdx.size(); i++) {
                String indexName = relName + "_Attr" + vectorAttrIdx.get(i)
                + "_h" + h + "_L" + L;
            
                try {
                    PageId headerPid = SystemDefs.JavabaseDB.get_file_entry(indexName);
                    if (headerPid != null) {
                        System.out.println("Found old LSHF index: " + indexName + ", header page ID: " + headerPid.pid);
                        SystemDefs.JavabaseDB.delete_file_entry(indexName); // 刪掉DB裡登記
                        SystemDefs.JavabaseBM.freePage(headerPid);          // 把headerPage釋放
                        System.out.println("Deleted LSHF index: " + indexName);

                         // 同時清除 L 個 layer BTree files
                        for (int l = 0; l < L; l++) {
                            String layerName = indexName + "_layer" + l;
                            PageId layerPid = SystemDefs.JavabaseDB.get_file_entry(layerName);
                            if (layerPid != null) {
                                SystemDefs.JavabaseDB.delete_file_entry(layerName);
                                SystemDefs.JavabaseBM.freePage(layerPid);
                                System.out.println("Deleted BTree layer: " + layerName);
                            }
                        }



                    } else {
                        System.out.println("No old LSHF index found for " + indexName + ", ok.");
                    }
                } catch (Exception e) {
                    System.out.println("Warning cleaning index: " + indexName + ", " + e.getMessage());
                }
            }



            // Create heapfile
            Heapfile hf = new Heapfile(relName);

            // Create LSH Indexes
            LSHFIndexFile[] vectorIndexes = new LSHFIndexFile[vectorAttrIdx.size()];
            for (int i = 0; i < vectorAttrIdx.size(); i++) {
                String indexName = relName + "_Attr" + vectorAttrIdx.get(i)
                                 + "_h" + h + "_L" + L;
                vectorIndexes[i] = new LSHFIndexFile(
                    indexName,
                    h,  // hash 函数个数
                    L,  // 层数
                    1,  // nAttrs
                    new AttrType[]{ new AttrType(AttrType.attrVector100D) }
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

                // Insert into vector indexes
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
            System.out.printf("Page reads:  %d\nPage writes: %d\n",
            PCounter.rcounter, PCounter.wcounter);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
