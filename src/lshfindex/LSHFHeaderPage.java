package lshfindex;

import diskmgr.*;
import global.*;
import heap.HFPage;

import java.io.*;

/**
 * LSHFHeaderPage extends HFPage to store LSH forest metadata:
 * - Number of layers (L) and number of hash functions (h)
 * - An array of header page IDs (one per layer, each as an int, 4 bytes)
 * - For each layer and each hash function, 100 a values (stored as bytes)
 */
public class LSHFHeaderPage extends HFPage {

    private static boolean DEBUG = true;

    public static final int OFFSET_CUSTOM = DPFIXED;       // DPFIXED (usually 20) reserved for HFPage’s own header
    public static final int OFFSET_MAGIC = OFFSET_CUSTOM;    // Use the first 4 bytes for the magic number
    public static final int MAGIC_VALUE = 1989;              // Same magic number as used in BTreeHeaderPage

    public static final int OFFSET_L = OFFSET_MAGIC + 4;     // store L (num of layers)
    public static final int OFFSET_h = OFFSET_L + 4;           // store h (num of hash functions)
    public static final int OFFSET_LAYER_PAGEIDS = OFFSET_h + 4;  // then L * 4 bytes for header page IDs
    // a-values block: For each layer and each hash function, 100 bytes:
    // Total a-values bytes = L * h * 100
    public static final int OFFSET_A_VALUES = OFFSET_LAYER_PAGEIDS;   // then L * 4 bytes are used, so:
    // Actually, more precisely, 
    // OFFSET_A_VALUES = OFFSET_LAYER_PAGEIDS + (L * 4)

    // Let:
    // OFFSET_ATTR_COUNT = OFFSET_A_VALUES + (L * h * 100)
    // OFFSET_ATTR_TYPES  = OFFSET_ATTR_COUNT + 4
    public static final int OFFSET_ATTR_COUNT = OFFSET_A_VALUES + /*(L * h * 100)*/ 0; // We'll compute dynamically.
    // Since L and h aren’t known at compile time, we'll compute OFFSET_A_VALUES and OFFSET_ATTR_COUNT in the constructor.
    
    // We'll store our custom metadata sequentially:
    // [Magic (4) | L (4) | h (4) | LayerIDs (L*4) | aValues (L*h*100) | nAttrs (4) | attrTypes (nAttrs*4) ]
    
    // We assume that the overall space (from OFFSET_CUSTOM up to MAX_SPACE) is sufficient.

    // Constructor to create a new LSHFHeaderPage with given L and h
    public LSHFHeaderPage(/*PageId pageNo,*/ int L, int h, int nAttrs, AttrType[] attrTypes) throws Exception {
        super();

        try {
            Page apage = new Page();
            PageId pageNo = SystemDefs.JavabaseBM.newPage(apage, 1);
            // CHECK
            if (pageNo == null) {
                System.out.println("construct header page failed");
            }
            this.init(pageNo, apage);
        } catch (Exception e) {
            System.out.println("construct header page failed");
        }
        // for (int i = OFFSET_CUSTOM; i < MAX_SPACE; i++) {
        //     data[i] = 0;
        // }

        // Write magic number first.
        if (DEBUG) {
            System.out.println("[LSHFHeaderPage] Setting magic to: " + MAGIC_VALUE);
        }
        Convert.setIntValue(MAGIC_VALUE, OFFSET_MAGIC, data);

        // Write the number of layers and h.
        if (DEBUG) {
            System.out.println("[LSHFHeaderPage] numLayers: " + L);
            System.out.println("[LSHFHeaderPage] numHashFuncs: " + h);
        }
        Convert.setIntValue(L, OFFSET_L, data);
        Convert.setIntValue(h, OFFSET_h, data);

        if (DEBUG) {
            int storedL = Convert.getIntValue(OFFSET_L, data);
            int storedh = Convert.getIntValue(OFFSET_h, data);
            System.out.println("[LSHFHeaderPage:New] Written L = " + storedL + ", h = " + storedh);
            System.out.println("magic?  " + Convert.getIntValue(OFFSET_MAGIC, data));
            System.out.println("L?      " + Convert.getIntValue(OFFSET_L, data));
            System.out.println("h?      " + Convert.getIntValue(OFFSET_h, data));
            System.out.println("==== Dump LSHFHeaderPage Header ====");
            for (int i = 0; i < 36; i += 4) {
                int val = Convert.getIntValue(i, data);
                System.out.printf("Offset %2d: %d (0x%08X)%n", i, val, val);
            }
        }

        // Initialize the array of header page IDs for each layer.
        int offset = OFFSET_LAYER_PAGEIDS;
        for (int i = 0; i < L; i++) {
            // Set to INVALID_PAGE (assumed defined elsewhere in GlobalConst)
            Convert.setIntValue(INVALID_PAGE, offset, data);
            offset += 4;
        }

        // Initialize the area for a values to zero.
        int offsetA = OFFSET_LAYER_PAGEIDS + L * 4;
        int totalABytes = L * h * 100;  // total bytes for a values.
        for (int i = 0; i < totalABytes; i++) {
            data[offsetA + i] = 0;
        }

        // Now, store the attribute types.
        int offsetAttrCount = offsetA + totalABytes;  // This is OFFSET_ATTR_COUNT.
        Convert.setIntValue(nAttrs, offsetAttrCount, data);
        int offsetAttrTypes = offsetAttrCount + 4;      // This is OFFSET_ATTR_TYPES.
        // Each attribute type is stored as an int. We assume attrTypes != null and length == nAttrs.
        for (int i = 0; i < nAttrs; i++) {
            Convert.setIntValue(attrTypes[i].attrType, offsetAttrTypes, data);
            offsetAttrTypes += 4;
        }
        
        if (DEBUG) {
            System.out.println("[LSHFHeaderPage:New] Dumping header from offset " + OFFSET_CUSTOM + " to " + offsetAttrTypes);
            for (int i = OFFSET_CUSTOM; i < offsetAttrTypes; i += 4) {
                int val = Convert.getIntValue(i, data);
                System.out.printf("Offset %2d: %d (0x%08X)%n", i, val, val);
            }
        }
    }

    // constructor to open an existing header page
    public LSHFHeaderPage(PageId pageno)
    throws Exception {
        super();
        try {
            System.out.println("[Read Header Test] try open pageno: " + pageno.pid);
            SystemDefs.JavabaseBM.pinPage(pageno, this, false/*Rdisk*/);
        } catch (Exception e) {
            System.out.println("[LSHFHeaderPage] pinpage failed.");
        }

        if (DEBUG) {
            int storedL = Convert.getIntValue(OFFSET_L, data);
            int storedh = Convert.getIntValue(OFFSET_h, data);
            System.out.println("[LSHFHeaderPage:New] Written L = " + storedL + ", h = " + storedh);
            System.out.println("magic?  " + Convert.getIntValue(OFFSET_MAGIC, data));
            System.out.println("L?      " + Convert.getIntValue(OFFSET_L, data));
            System.out.println("h?      " + Convert.getIntValue(OFFSET_h, data));
            System.out.println("==== Dump LSHFHeaderPage Header ====");
            for (int i = 0; i < 36; i += 4) {
                int val = Convert.getIntValue(i, data);
                System.out.printf("Offset %2d: %d (0x%08X)%n", i, val, val);
            }
        }

        // Check magic number
        int magic = Convert.getIntValue(OFFSET_MAGIC, data);
        if (magic != MAGIC_VALUE) {
            throw new IOException("Header page not in expected format. Magic number mismatch: " + magic);
        }
    }

    PageId getPageId() throws IOException {
        return getCurPage();
    }

    public LSHFHeaderPage(Page page) {
        super(page);
    }

    // Accessor: retrieve the number of layers.
    public int getNumLayers() throws IOException {
        return Convert.getIntValue(OFFSET_L, data);
    }

    // Accessor: retrieve the number of hash functions per layer.
    public int getNumHashFunctions() throws IOException {
        return Convert.getIntValue(OFFSET_h, data);
    }

    // Get the header page ID for a given layer.
    public PageId getLayerHeaderPageId(int layer) throws IOException {
        int L = getNumLayers();
        if (layer < 0 || layer >= L)
            throw new IllegalArgumentException("Layer out of range");
        int offset = OFFSET_LAYER_PAGEIDS + layer * 4;
        int pid = Convert.getIntValue(offset, data);
        PageId ret = new PageId();
        ret.pid = pid;
        return ret;
    }

    // Set the header page ID for a given layer.
    public void setLayerHeaderPageId(int layer, PageId pid) throws IOException {
        int L = getNumLayers();
        if (layer < 0 || layer >= L)
            throw new IllegalArgumentException("Layer out of range");
        int offset = OFFSET_LAYER_PAGEIDS + layer * 4;
        Convert.setIntValue(pid.pid, offset, data);
    }

    // Get a specific a value. For a given layer l, hash function index i, and dimension j.
    public byte getAValue(int layer, int hashIndex, int j) throws IOException {
        int L = getNumLayers();
        int h = getNumHashFunctions();
        if (layer < 0 || layer >= L || hashIndex < 0 || hashIndex >= h || j < 0 || j >= 100)
            throw new IllegalArgumentException("Index out of range");
        int offsetA = OFFSET_LAYER_PAGEIDS + L * 4;
        int pos = offsetA + (layer * h * 100) + (hashIndex * 100) + j;
        return data[pos];
    }

    // Set a specific a value.
    public void setAValue(int layer, int hashIndex, int j, byte value) throws IOException {
        int L = getNumLayers();
        int h = getNumHashFunctions();
        if (layer < 0 || layer >= L || hashIndex < 0 || hashIndex >= h || j < 0 || j >= 100)
            throw new IllegalArgumentException("Index out of range");
        int offsetA = OFFSET_LAYER_PAGEIDS + L * 4;
        int pos = offsetA + (layer * h * 100) + (hashIndex * 100) + j;
        data[pos] = value;
    }

    // For convenience, you might also add methods to write/read an entire hash function vector.
    // For example, get all 100 a values for a given layer and hashIndex:
    public byte[] getAValuesForHash(int layer, int hashIndex) throws IOException {
        int L = getNumLayers();
        int h = getNumHashFunctions();
        if (layer < 0 || layer >= L || hashIndex < 0 || hashIndex >= h)
            throw new IllegalArgumentException("Index out of range");
        byte[] arr = new byte[100];
        int offsetA = OFFSET_LAYER_PAGEIDS + L * 4;
        int basePos = offsetA + (layer * h * 100) + (hashIndex * 100);
        System.arraycopy(data, basePos, arr, 0, 100);
        return arr;
    }

    // And to set them at once:
    public void setAValuesForHash(int layer, int hashIndex, byte[] arr) throws IOException {
        if (arr.length != 100)
            throw new IllegalArgumentException("Length must be 100");
        int L = getNumLayers();
        int h = getNumHashFunctions();
        if (layer < 0 || layer >= L || hashIndex < 0 || hashIndex >= h)
            throw new IllegalArgumentException("Index out of range");
        int offsetA = OFFSET_LAYER_PAGEIDS + L * 4;
        int basePos = offsetA + (layer * h * 100) + (hashIndex * 100);
        System.arraycopy(arr, 0, data, basePos, 100);
    }

    // Get the number of attributes.
    public int getAttrCount() throws IOException {
        int numLayers = getNumLayers();
        int numHashFuncs = getNumHashFunctions();
        int offsetAttrCount = OFFSET_LAYER_PAGEIDS + (numLayers * 4) + (numLayers * numHashFuncs * 100);
        return Convert.getIntValue(offsetAttrCount, data);
    }
    
    // Get the attribute types as an array.
    public int[] getAttrTypes() throws IOException {
        int nAttrs = getAttrCount();
        int[] types = new int[nAttrs];
        int numLayers = getNumLayers();
        int numHashFuncs = getNumHashFunctions();
        int offset = OFFSET_LAYER_PAGEIDS + (numLayers * 4) + 4; // after attr count field
        for (int i = 0; i < nAttrs; i++) {
            types[i] = Convert.getIntValue(offset, data);
            offset += 4;
        }
        return types;
    }

    // Set attribute types.
    public void setAttrTypes(int[] attrTypes) throws IOException {
        int nAttrs = attrTypes.length;
        int numLayers = getNumLayers();
        int numHashFuncs = getNumHashFunctions();
        int offset = OFFSET_LAYER_PAGEIDS + (numLayers * 4) + 0; // first write attr count here
        Convert.setIntValue(nAttrs, offset, data);
        offset += 4;
        for (int i = 0; i < nAttrs; i++) {
            Convert.setIntValue(attrTypes[i], offset, data);
            offset += 4;
        }
    }
}
