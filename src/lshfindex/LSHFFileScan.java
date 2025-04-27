package lshfindex;

import btree.*;
import btree.BTFileScan;
import global.*;
import diskmgr.*;
import bufmgr.*;
import heap.*;
import java.io.*;
import java.util.*;

// Helper class to pair a tuple with its computed Euclidean distance.
// class TupleDistance {
//     Tuple tuple;
//     double distance;
//     public TupleDistance(Tuple tuple, double distance) {
//         this.tuple = tuple;
//         this.distance = distance;
//     }
// }

/**
 * LSHFFileScan provides a scan interface for an LSH forest.
 * The forest is implemented as a set of B-tree indexes.
 * The scan uses a target query vector (stored in "query") to evaluate distances.
 */
public class LSHFFileScan /*extends IndexFileScan*/ implements GlobalConst {

    private static boolean DEBUG = true;

    // Reference to the LSH forest index file that contains multiple B-trees.
    private LSHFIndexFile lshfIndexFile;
    // The query vector (target) used by our range and NN scans.
    private Vector100Dtype query;
    // Total number of layers and hash functions per layer.
    private int L;
    private int h;
    // For NN scan: a list to store candidate tuples along with their distances.
    // For Range scan: a list to store candidates meeting the distance criterion.

    private Heapfile dataHeapFile;
    
    // Constructor: provide the LSH index file and the query vector.
    public LSHFFileScan(LSHFIndexFile indexFile, Heapfile dataFile, Vector100Dtype query) throws Exception {
        this.lshfIndexFile = indexFile;
        this.dataHeapFile = dataFile;
        this.query = query;
        this.L = indexFile.getL();
        this.h = indexFile.getH();
        if (DEBUG) {
            System.out.println("Heap file record count: " + dataHeapFile.getRecCnt());
        }
    }

    /**
     * Perform a range scan on the LSH forest.
     * The input key (a KeyClass whose toString() is the bit string) is used as the initial hash value.
     * If fewer than 70% of scanned vectors in the current range have Euclidean distance below
     * distanceThreshold, then the range is increased by omitting one additional bit.
     * 
     * @param key the starting key (bit string) for the scan.
     * @param distanceThreshold the distance threshold (in Euclidean metric)
     * @return An array of candidate tuples satisfying the range condition.
     * @throws ScanIteratorException if scan iteration fails.
     */
    public Tuple[] LSHFFileRangeScan(KeyClass key, int distanceThreshold, AttrType[] type, int queryField /*, short[] strSizes, short numFlds*/) throws ScanIteratorException {
        int ignoreBits = 0;
        List<Tuple> resultCandidates = new ArrayList<>();
        boolean foundSatisfactory = false;
        String bitStr = key.toString();

        short[] strSizes = new short[1];
        strSizes[0] = 30;

        short numFlds = (short)type.length;

        // A set to record the unique identifiers (RIDs) of the tuples we've already seen.
        HashSet<String> seen = new HashSet<>();
        
        while (!foundSatisfactory && ignoreBits <= bitStr.length()) {
            // Calculate lower and upper bounds based on the current precision.
            int[] range = getPrefixRange(bitStr, ignoreBits);
            int lowerBound = range[0];
            int upperBound = range[1];
            if (DEBUG) {
                System.out.println("[LSHFFileRangeScan] ignoreBits: " + ignoreBits +
                                   ", Range: [" + lowerBound + ", " + upperBound + "]");
            }
            // For simplicity, collect candidates from all layers.
            List<Tuple> allCandidates = new ArrayList<>();
            int totalCount = 0;
            for (int layer = 0; layer < L; layer++) {
                try {
                    BTreeFile btree = lshfIndexFile.getTree(layer);
                    // Create a range scan using lo_key and hi_key.
                    // Here we build KeyClass objects (e.g., IntegerKey) from the integer bounds.
                    KeyClass loKey = new IntegerKey(lowerBound);
                    KeyClass hiKey = new IntegerKey(upperBound);
                    BTFileScan treeScan = (BTFileScan) btree.new_scan(loKey, hiKey);
                    if (treeScan == null) continue;
                    KeyDataEntry entry;
                    while ((entry = treeScan.get_next()) != null) {
                        totalCount++;
                        Tuple tup = fetchTuple(entry);
                        tup.setHdr(numFlds, type, strSizes);

                        RID rid = ((LeafData)entry.data).getData();
                        String ridStr = rid.pageNo.pid + ":" + rid.slotNo;
                        // Skip if we've seen this tuple already.
                        if (!seen.add(ridStr)) {
                            continue;
                        }

                        // Extract the vector from tup. (using sample data 2 to test, fld 2 and fld 4 is 100D.)
                        // NEED FIX
                        
                        if (DEBUG) {
                            int tupLen = tup.getLength();
                            System.out.println("Length of tuple:" + tupLen);
                            short tupSize = tup.size();
                            System.out.println("Size of tuple:" + tupSize);
                            System.out.println("[Range Search Test] tuple content:");
                            tup.print(type);
                            System.out.println("[Range Search Test] tuple fldCnt: " + tup.noOfFlds());
                        }
                        Vector100Dtype candidateVector = tup.get100DVectFld(queryField);
                        double dist = computeEuclideanDistance(query, candidateVector);
                        if (dist < distanceThreshold) {
                            allCandidates.add(tup);
                        }
                        System.out.println("pageNo of RID: " + entry.data.toString());
                    }

                    
                    // System.out.println("slotNo of RID: " + entry..slotNo);

                    treeScan.DestroyBTreeFileScan();
                } catch (Exception e) {
                    throw new ScanIteratorException(e, "Error scanning layer " + layer);
                }
            }
            if (DEBUG) {
                System.out.println("[LSHFFileRangeScan] total scanned: " + totalCount +
                                   ", within threshold: " + allCandidates.size());
            }
            // If at least 70% of total scanned candidates satisfy the threshold, we stop.
            if (totalCount > 0 && ((double) allCandidates.size() / totalCount) >= 0.7) {
                resultCandidates.addAll(allCandidates);
                foundSatisfactory = true;
            } else {
                ignoreBits++; // Widen the range by ignoring one more bit.
            }
        }
        return resultCandidates.toArray(new Tuple[0]);
    }

    /**
     * Perform a nearest-neighbor (NN) scan on the LSH forest.
     * This method gathers candidates from all layers whose key values fall
     * in the same range as the provided key, computes their Euclidean distances
     * to the query vector, and then returns the 'count' nearest tuples.
     *
     * @param key the starting key (bit string) for the scan.
     * @param count the number of nearest neighbors to return.
     * @return an array of the nearest candidate tuples.
     * @throws ScanIteratorException if scanning fails.
     */
    public Tuple[] LSHFFileNNScan(KeyClass key, int count, AttrType[] type, int queryField) throws ScanIteratorException {
        int ignoreBits = 0;
        List<TupleDistance> candidateList = new ArrayList<>();
        String bitStr = key.toString();

        short[] strSizes = new short[1];
        strSizes[0] = 30;

        short numFlds = (short)type.length;

        HashSet<String> seen = new HashSet<>();

        // Loop until we have sufficient candidates (or we drop all bits).
        while (ignoreBits <= bitStr.length()) {
            // Determine the current range.
            int[] range = getPrefixRange(bitStr, ignoreBits);
            int lowerBound = range[0];
            int upperBound = range[1];
            candidateList.clear();
            for (int layer = 0; layer < L; layer++) {
                try {
                    BTreeFile btree = lshfIndexFile.getTree(layer);
                    KeyClass loKey = new IntegerKey(lowerBound);
                    KeyClass hiKey = new IntegerKey(upperBound);
                    BTFileScan treeScan = (BTFileScan) btree.new_scan(loKey, hiKey);
                    if (treeScan == null) continue;
                    KeyDataEntry entry;
                    while ((entry = treeScan.get_next()) != null) {
                        RID rid = ((LeafData)entry.data).getData();
                        String ridStr = rid.pageNo.pid + ":" + rid.slotNo;
                        if (!seen.add(ridStr)) {
                            continue;
                        }

                        Tuple tup = fetchTuple(entry);
                        tup.setHdr(numFlds, type, strSizes);
                        Vector100Dtype candidateVector = tup.get100DVectFld(queryField);
                        double dist = computeEuclideanDistance(query, candidateVector);
                        candidateList.add(new TupleDistance(tup, dist));
                        if (DEBUG) {
                            int tupLen = tup.getLength();
                            System.out.println("Length of tuple:" + tupLen);
                            short tupSize = tup.size();
                            System.out.println("Size of tuple:" + tupSize);
                            System.out.println("[Range Search Test] tuple content:");
                            tup.print(type);
                            System.out.println("[Range Search Test] tuple fldCnt: " + tup.noOfFlds());
                        }
                    }
                    treeScan.DestroyBTreeFileScan();
                } catch (Exception e) {
                    throw new ScanIteratorException(e, "Error scanning layer " + layer);
                }
            }
            if (candidateList.size() >= count) {
                break; // we have enough candidates
            }
            ignoreBits++; // widen the scan range if not enough candidates
        }

        // Sort candidates by Euclidean distance.
        Collections.sort(candidateList, new Comparator<TupleDistance>() {
            public int compare(TupleDistance td1, TupleDistance td2) {
                return Double.compare(td1.distance, td2.distance);
            }
        });
        // Return the top 'count' neighbors.
        Tuple[] results = new Tuple[Math.min(count, candidateList.size())];
        for (int i = 0; i < results.length; i++) {
            results[i] = candidateList.get(i).tuple;
        }
        return results;
    }

    // A helper method to compute Euclidean distance between two 100D vectors.
    private double computeEuclideanDistance(Vector100Dtype v1, Vector100Dtype v2) {
        short[] dims1 = v1.getDimension();
        short[] dims2 = v2.getDimension();
        double sum = 0.0;
        for (int i = 0; i < 100; i++) {
            double diff = dims1[i] - dims2[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
    
    private Tuple fetchTuple(KeyDataEntry entry) throws ScanIteratorException {
        if (entry == null || entry.data == null) {
            throw new ScanIteratorException(null, "Entry or its data is null.");
        }
    
        if (!(entry.data instanceof LeafData)) {
            throw new ScanIteratorException(null, "Entry does not contain a RID; it's not a leaf entry.");
        }
    
        RID rid = ((LeafData)entry.data).getData();
        // RID rid = (entry.data).getData();
        try {
            System.out.println("RID of tuple" + rid.toString());
            return dataHeapFile.getRecord(rid);
        } catch (Exception e) {
            throw new ScanIteratorException(e, "Error fetching tuple for RID: " + rid);
        }
    }
    
    /**
     * Given a bit string and a number of bits to ignore at the end, compute
     * the prefix range as lower and upper bounds.
     * This is the same helper method provided.
     * @param bitString the bit string representing the hash key.
     * @param ignoreBits the number of bits to ignore from the end.
     * @return an int array of length 2: {lowerBound, upperBound}.
     */
    public static int[] getPrefixRange(String bitString, int ignoreBits) {

        if (DEBUG) {
            System.out.println("[Range Search Test] bitString: " + bitString);
        }

        int prefixLength = bitString.length() - ignoreBits;
        if (prefixLength < 0) {
            throw new IllegalArgumentException("Cannot ignore more bits than the string length.");
        }
        // Create prefix by keeping the first (length - ignoreBits) bits
        String prefix = bitString.substring(0, prefixLength);
        // Compute range by appending all 0s (for lower bound) and all 1s (for upper bound)
        int lowerBound = Integer.parseInt(prefix + "0".repeat(ignoreBits), 2);
        int upperBound = Integer.parseInt(prefix + "1".repeat(ignoreBits), 2);
        return new int[]{lowerBound, upperBound};
    }
    
    // Class to pair a tuple with its computed distance.
    private class TupleDistance {
        Tuple tuple;
        double distance;
        TupleDistance(Tuple tuple, double distance) {
            this.tuple = tuple;
            this.distance = distance;
        }
    }
}
