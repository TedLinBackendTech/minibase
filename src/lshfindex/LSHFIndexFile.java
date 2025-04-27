package lshfindex;

import btree.*;
import bufmgr.*;
import catalog.*;
import diskmgr.Page;
import heap.*;
import global.AttrType;
import global.Convert;
import global.IndexType;
import global.PageId;
import global.RID;
import global.SystemDefs;
import global.Vector100Dtype;
import java.io.IOException;
import java.util.Random;

/**
 * LSHFIndexFile implements a fully complete self-tuning LSH-Forest index.
 *
 * For each of the L layers, we maintain a prefix tree (built using the btree package
 * as a template) that stores keys and pointers. Index pages in these trees hold
 * <key, PageID> pairs while leaf pages hold <key, RID> pairs.
 *
 * The hash function for Euclidean distance is defined as:
 *    h(x) = floor((a * x + b) / W)
 * where 'a' is drawn from a Gaussian (N(0,1)) distribution and b is drawn uniformly
 * from [0, W]. The final key is a concatenation of h such values.
 */
public class LSHFIndexFile {

    private static boolean DEBUG = true;

    private String fileName;
    private int h;        // number of hash functions per layer (i.e., number of hash values to concatenate)
    private int L;        // number of layers
    private BTreeFile[] prefixTrees;  // one prefix tree per layer

    // For each layer and each hash function, we store a random projection vector "a"
    // and a corresponding offset "b". They are used to compute:
    //   h(x) = floor((a * x + b) / W)
    // aValues[layer][i] is a 100-dimensional vector (array of doubles)
    private byte[][][] aValues;
    // bOffsets[layer][i] is the offset for the i-th hash function in the given layer.
    // private double[][] bOffsets;

    // Bucket width parameter from Andoni Indyk. This controls the quantization.
    // private final double W = 5000.0;

    private Random rand;

    private static int M = 127;        // max magnitude we allow (fits in signed byte)
    private static double scale = 20;  // 20σ → most values land in [-60,60]

    private LSHFHeaderPage headerPage;
    private PageId headerPageId;

    /**
     * Constructs a new LSHFIndexFile.
     * @param fileName the base file name used to create the prefix tree files.
     * @param h the number of hash functions (values to concatenate) per layer.
     * @param L the number of layers.
     * @throws Exception if initialization fails.
     */
    public LSHFIndexFile(String fileName, int h, int L, int nAttrs, AttrType[] attrTypes) throws Exception {
        this.fileName = fileName;
        this.h = h;
        this.L = L;
        this.rand = new Random();
    
        // Try to get the header page ID from the file entry.
        headerPageId = SystemDefs.JavabaseDB.get_file_entry(fileName);
        
        if (headerPageId == null) {
            // --- HEADER DOES NOT EXIST: Create a new header page and generate aValues ---
            
            // Allocate aValues and generate new ones.
            System.out.println("[Read Header Test] hash function for " + fileName);
            aValues = new byte[L][h][100];
            for (int l = 0; l < L; l++) {
                for (int i = 0; i < h; i++) {
                    for (int j = 0; j < 100; j++) {
                        double g = rand.nextGaussian();       // ~N(0,1)
                        int q = (int)Math.round(g * scale);      // quantize using scale
                        if (q > M) q = M;
                        if (q < -M) q = -M;
                        System.out.print(q + "; ");
                        aValues[l][i][j] = (byte)q;
                    }
                }
            }

            System.out.println("[Read Header Test] a values generated");
            
            // Create a new header page.
            // headerPageId = new PageId();
            headerPage = new LSHFHeaderPage(/*headerPageId,*/ L, h, nAttrs, attrTypes);  // No-arg constructor creates a new page.
            headerPageId = headerPage.getPageId();
            SystemDefs.JavabaseDB.add_file_entry(fileName, headerPageId);
            
            if (DEBUG) {
                System.out.println("[Read Header Test] headerPageId: " + headerPageId.pid);
            }

            SystemDefs.JavabaseDB.add_file_entry(fileName, headerPageId);
            System.out.println("creating headerPageId = " + headerPageId.pid);
            
            // Store L and h in the header page (using the known offsets in LSHFHeaderPage)
            Convert.setIntValue(L, LSHFHeaderPage.OFFSET_L, headerPage.getHFpageArray());
            Convert.setIntValue(h, LSHFHeaderPage.OFFSET_h, headerPage.getHFpageArray());
            // The LSHFHeaderPage constructor (init) should have already created space for L layer IDs and a-values.
            
            // Now, write the generated aValues into the header page.
            for (int l = 0; l < L; l++) {
                for (int i = 0; i < h; i++) {
                    for (int j = 0; j < 100; j++) {
                        headerPage.setAValue(l, i, j, aValues[l][i][j]);
                    }
                }
            }
        } else {
            // --- HEADER EXISTS: Open the header page and read the stored aValues ---
            headerPage = new LSHFHeaderPage(headerPageId);
            
            // Read L and h from header page.
            int numLayers = headerPage.getNumLayers();
            int numHashFuncs = headerPage.getNumHashFunctions();
            // For consistency, one may want to check that numLayers == L and numHashFuncs == h.
            if (numLayers != L || numHashFuncs != h) {
                throw new Exception("Mismatch in L or h between catalog and parameters");
            }
            
            // Allocate aValues array and load from the header page.
            aValues = new byte[L][h][100];
            for (int l = 0; l < L; l++) {
                for (int i = 0; i < h; i++) {
                    for (int j = 0; j < 100; j++) {
                        aValues[l][i][j] = headerPage.getAValue(l, i, j);
                    }
                }
            }
        }
        
        // Now, initialize one prefix tree (BTreeFile) per layer.
        prefixTrees = new BTreeFile[L];
        for (int l = 0; l < L; l++) {
            // Each prefix tree uses a unique file name (e.g., fileName_layer0, fileName_layer1, ...).
            String layerName = fileName + "_layer" + l;
            prefixTrees[l] = new BTreeFile(layerName, AttrType.attrInteger, 4, 1);
            
            // Get this B-tree's header page ID.
            PageId btHeaderId = prefixTrees[l].getHeaderPage().getCurPage();
            
            // Record this header page ID in our LSHFHeaderPage.
            headerPage.setLayerHeaderPageId(l, btHeaderId);
        }

        System.out.println("[LSHFIndexFile] Finished creating file. PageId: " + headerPageId.pid);
    }
    

    // constructor: open existing index file
    public LSHFIndexFile(String fileName) throws Exception {
        // Save file name.
        this.fileName = fileName;
        
        // Open the header page.
        String headerFileName = fileName;
        
        // Get the page ID of the header page from the database catalog.
        PageId headerPageId = SystemDefs.JavabaseDB.get_file_entry(headerFileName);
        System.out.println("[Read Header Test] try open: " + headerFileName);
        if (headerPageId == null) {
            throw new Exception("Header page not found for file: " + headerFileName);
        }

        System.out.println("opening: headerPageId = " + headerPageId.pid);
        
        this.headerPage = new LSHFHeaderPage(headerPageId);
        // Pin the header page from the buffer manager.
        SystemDefs.JavabaseBM.pinPage(headerPageId, this.headerPage, false);
        
        // Open the header page as an LSHFHeaderPage (assumes a constructor exists that accepts a Page).
        // this.headerPage = new LSHFHeaderPage(headerPage);
        
        // Read the number of layers (L) and number of hash functions per layer (h)
        int numLayers = headerPage.getNumLayers();
        int numHashFuncs = headerPage.getNumHashFunctions();
        this.L = numLayers;
        this.h = numHashFuncs;
        System.out.println("[Read Header Test] numLayers: " + this.L);
        System.out.println("[Read Header Test] numHashFuncs: " + this.h);
        
        // Allocate the in-memory array for aValues and load them from the header page.
        aValues = new byte[numLayers][numHashFuncs][100];
        for (int l = 0; l < numLayers; l++) {
            for (int i = 0; i < numHashFuncs; i++) {
                for (int j = 0; j < 100; j++) {
                    aValues[l][i][j] = headerPage.getAValue(l, i, j);
                }
            }
        }
        
        // Open the prefix trees for each layer.
        prefixTrees = new BTreeFile[numLayers];
        for (int l = 0; l < numLayers; l++) {
            // Each BTreeFile is assumed to have a unique file name.
            String layerName = fileName + "_layer" + l;
            prefixTrees[l] = new BTreeFile(layerName);  // Open existing B-tree index.
        }
        
        // Optionally, initialize random if needed.
        this.rand = new Random();
        
        // Unpin header page (if desired—depending on whether headerPage is kept in memory)
        SystemDefs.JavabaseBM.unpinPage(headerPageId, false);
    }

    /**
     * Computes the concatenated hash for a given vector on a specific layer using a specified prefix length.
     * The computed key is a string concatenation of h_i(x) values (each an integer bucket)
     * computed as: floor((a_i * x + b_i) / W)
     *
     * @param vector the 100D vector.
     * @param layer the layer for which the hash is computed.
     * @param prefixLength the number of hash functions to use (allows self-tuning).
     * @return a String representing the concatenated hash value.
     */
    /**
    * Compute the LSH hash prefix for `vector` at `layer`,
    * using up to `prefixLength` hash functions.
    * aValues[layer][i][j] is a byte in [-127,127].
    * vector.getDimension()[j] is a short.
    */
    public String computeHash(Vector100Dtype vector, int layer, int prefixLength) {
        short[] dims = vector.getDimension();         // the 100D vector components
        StringBuilder sb = new StringBuilder();
        int len = Math.min(prefixLength, h);          // only use up to h hash functions

        for (int i = 0; i < len; i++) {
            int dot = 0;
            // accumulate in an int; byte*short → int
            for (int j = 0; j < 100; j++) {
                dot += aValues[layer][i][j] * dims[j];
            }
            // bit = 1 if dot > 0, else 0
            sb.append(dot > 0 ? '1' : '0');
        }

        return sb.toString();
    }

    // public String computeHash(Vector100Dtype vector, int layer, int prefixLength) {
    //     short[] dims = vector.getDimension();
    //     StringBuilder sb = new StringBuilder();
    //     int len = Math.min(prefixLength, h);

    //     for (int i = 0; i < len; i++) {
    //         double dot = 0.0;
    //         for (int j = 0; j < 100; j++) {
    //             dot += aValues[layer][i][j] * dims[j];
    //         }
    //         // Compute the hash value using floor((dot + b) / W)
    //         int hashVal = (int)(Math.floor(Math.abs(dot/* + bOffsets[layer][i]*/)));
    //         sb.append(hashVal);
    //     }

    //     // double dot = 0.0;
    //     // for (int j = 0; j < 100; j++) {
    //     //     dot += aValues[layer][hashno][j] * dims[j];
    //     // }
    //     // // Compute the hash value using floor((dot + b) / W)
    //     // int hashVal = (int)Math.floor((dot + bOffsets[layer][hashno]) / W);
    //     // sb.append(hashVal);

    //     return sb.toString();
    // }

    /**
     * Inserts a new 100D vector (with its corresponding RID) into the index.
     * For each layer, the full h-value hash is computed from the vector, then a Vector100DKey is created
     * and inserted into that layer's prefix tree.
     *
     * @param vector the 100D vector to insert.
     * @param rid the record identifier for the corresponding data tuple.
     * @throws Exception if insertion fails.
     */
    public void insert(Vector100Dtype vector, RID rid) throws Exception {
        for (int layer = 0; layer < L; layer++) {
            // Compute the full h-value hash for this layer.
            String hashVal = computeHash(vector, layer, h);
            // Create a Vector100DKey using the computed hash string.
            Vector100DKey key = new Vector100DKey(hashVal);
            // Insert into the corresponding prefix tree.
            IntegerKey convertedKey = LSHF.convertKey(key);

            prefixTrees[layer].insert(convertedKey, rid);
        }
    }

    public int getL() {
        return L;
    }

    public int getH() {
        return h;
    }

    public BTreeFile getTree(int layer) {
        return prefixTrees[layer];
    }

    /** Close the LSHF index file.  Unpin header page.
     *@exception PageUnpinnedException  error from the lower layer
     *@exception InvalidFrameNumberException  error from the lower layer
     *@exception HashEntryNotFoundException  error from the lower layer
     *@exception ReplacerException  error from the lower layer
     */
    public void close()
    throws PageUnpinnedException, InvalidFrameNumberException, HashEntryNotFoundException, ReplacerException, IOException {
        for (int l = 0; l < L; l++) {
            // prefixTrees[l].closePrefixTree();
            prefixTrees[l].close();
            System.out.println("Prefix Tree " + l + " closed.");
        }
        if ( headerPage != null) {
            SystemDefs.JavabaseBM.unpinPage(headerPageId, true);
            headerPage = null;
        }
    }

    public void printAValues() throws IOException {
        // Get the number of layers and hash functions from the header page.
        int numLayers = headerPage.getNumLayers();
        int numHashFuncs = headerPage.getNumHashFunctions();
    
        System.out.println("LSH Forest A values:");
        for (int l = 0; l < numLayers; l++) {
            System.out.println("Layer " + l + ":");
            for (int i = 0; i < numHashFuncs; i++) {
                System.out.print("  Hash function " + i + ": ");
                for (int j = 0; j < 100; j++) {
                    // Retrieve each a value from the header page.
                    byte a = headerPage.getAValue(l, i, j);
                    System.out.print(a + " ");
                }
                System.out.println();  // newline after each hash function
            }
            System.out.println();      // extra newline after each layer
        }
    }
    
}
