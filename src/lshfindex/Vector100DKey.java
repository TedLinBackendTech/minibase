package lshfindex;

import global.Vector100Dtype;
import btree.KeyClass;

public class Vector100DKey extends KeyClass {

  private String key;

  public String toString(){
     return key.toString();
  }

  /** Class constructor
   *  @param     hashvalue   the value of the Vector 100D key to be set 
   */
  public Vector100DKey(String hashvalue) 
  { 
    key=new String(hashvalue);
  }

  /** get a copy of the vector 100D key
   *  @return the reference of the copy 
   */
  public String getKey() 
  {
    return new String(key);
  }

  /** set the integer key value
   */  
  public void setKey(String hashvalue) 
  { 
    key=new String(hashvalue);
  }
}
