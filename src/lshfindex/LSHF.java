package lshfindex;

import btree.*;
import global.*;
import java.io.*;

public class LSHF implements GlobalConst {

  private static boolean DEBUG = false;

  // new implementation helper function
  // binary conversion
  public static int bitStringToInt(String bitString) {
    return Integer.parseInt(bitString, 2);
  }

  public static int[] getPrefixRange(String bitString) {
    int prefixLength = bitString.length() - 1; // Ignore the last bit
    int lowerBound = Integer.parseInt(bitString.substring(0, prefixLength) + "0", 2);
    int upperBound = Integer.parseInt(bitString.substring(0, prefixLength) + "1", 2);
    return new int[]{lowerBound, upperBound};
  }

  // convert Vector100DKey to IntegerKey
  public static IntegerKey convertKey(Vector100DKey key) {
    // IntegerKey intKey = new IntegerKey(bitStringToInt(key.getKey()));
    return new IntegerKey(bitStringToInt(key.getKey()));
  }

  public int[] getPrefixRange(String bitString, int ignoreBits) {
    int prefixLength = bitString.length() - ignoreBits; 
    if (prefixLength < 0) {
        throw new IllegalArgumentException("Cannot ignore more bits than the string length.");
    }

    // Create prefix by keeping the first (length - ignoreBits) bits
    String prefix = bitString.substring(0, prefixLength);

    // Compute range by appending all 0s (lower bound) and all 1s (upper bound)
    int lowerBound = Integer.parseInt(prefix + "0".repeat(ignoreBits), 2);
    int upperBound = Integer.parseInt(prefix + "1".repeat(ignoreBits), 2);

    return new int[]{lowerBound, upperBound};
  }



  public final static int keyCompare(KeyClass key1, KeyClass key2)
    throws KeyNotMatchException {
      if (key1 instanceof Vector100DKey && key2 instanceof Vector100DKey) {
        return ((Vector100DKey) key1).getKey().compareTo(((Vector100DKey) key2).getKey());
      } else {
        throw new KeyNotMatchException(null, "Key types do not match");
      }
  }

  protected final static int getKeyLength(KeyClass key) 
    throws KeyNotMatchException, IOException {
      if (key instanceof Vector100DKey) {
        OutputStream out = new ByteArrayOutputStream();
        DataOutputStream outstr = new DataOutputStream(out);
        outstr.writeUTF(((Vector100DKey) key).getKey());
        return outstr.size();
      } else {
        throw new KeyNotMatchException(null, "Key type not supported");
      }
  }

  protected final static int getDataLength(short pageType) 
    throws NodeNotMatchException {
      if (pageType == NodeType.LEAF)
        return 8;
      else if (pageType == NodeType.INDEX)
        return 4;
      else
        throw new NodeNotMatchException(null, "Node types do not match");
  }

  protected final static int getKeyDataLength(KeyClass key, short pageType) 
    throws KeyNotMatchException, NodeNotMatchException, IOException {
      return getKeyLength(key) + getDataLength(pageType);
  }
}
