package iterator;

import java.io.IOException;
import global.*;
import heap.*;
import btree.*;
import lshfindex.*;

/**
 * INLJoins: Index‐Nested‐Loop Join operator.
 * Chooses B+‐tree for scalar joins or LSH forest (layer 0) for 100D vector joins.
 */
public class INLJoins extends Iterator {
  private static final boolean DEBUG = false;

  // input schemas
  private AttrType[] _in1, _in2;
  private short[] _s1, _s2;
  private int _len1, _len2;

  // join and projection spec
  private CondExpr[] _outFilter;
  private CondExpr[] _rightFilter;
  private FldSpec[] _projList;
  private int _nOutFlds;

  // outer iterator state
  private Iterator _outer;
  private Tuple _outerTuple;

  // inner storage file
  private Heapfile _innerHeap;

  // index choice and state
  private boolean _useVectorIndex;
  private BTreeFile _btIndex;         // for scalar
  private LSHFIndexFile _lshIndex;    // for vector
  private BTreeFile _lshTree;         // layer 0 tree
  private int _distanceThreshold;
  private BTFileScan _btScan;

  // join field positions
  private int _outerJoinFld, _innerJoinFld;

  // result tuple
  private Tuple _resultTuple;

  /**
   * Constructor
   */
  public INLJoins(
      AttrType[] in1, int len_in1, short[] t1_str_sizes,
      AttrType[] in2, int len_in2, short[] t2_str_sizes,
      int amt_of_mem,
      Iterator am1,
      String relationName,
      IndexType index,
      String indexName,
      CondExpr[] outFilter,
      CondExpr[] rightFilter,
      FldSpec[] proj_list,
      int n_out_flds
  ) throws Exception {
    // save schemas
    _in1 = in1;  _len1 = len_in1;  _s1 = t1_str_sizes;
    _in2 = in2;  _len2 = len_in2;  _s2 = t2_str_sizes;
    _outer = am1;
    _outFilter = outFilter;
    _rightFilter = rightFilter;
    _projList = proj_list;
    _nOutFlds = n_out_flds;

    // open inner heap file
    _innerHeap = new Heapfile(relationName);

    // identify join fields from rightFilter[0]
    CondExpr cond = rightFilter[0];
    FldSpec f1 = cond.operand1.symbol;
    FldSpec f2 = cond.operand2.symbol;
    if (f1.relation.key == RelSpec.outer && f2.relation.key == RelSpec.innerRel) {
      _outerJoinFld = f1.offset;
      _innerJoinFld = f2.offset;
    } else if (f2.relation.key == RelSpec.outer && f1.relation.key == RelSpec.innerRel) {
      _outerJoinFld = f2.offset;
      _innerJoinFld = f1.offset;
    } else {
      throw new Exception("Join operands must reference outer and inner");
    }

    // pick index implementation
    if (in1[_outerJoinFld-1].attrType == AttrType.attrVector100D) {
      _useVectorIndex = true;
      _distanceThreshold = cond.distance;
      _lshIndex = new LSHFIndexFile(indexName);
      // use layer 0 for join
      _lshTree = _lshIndex.getTree(0);
    } else {
      _useVectorIndex = false;
      _btIndex = new BTreeFile(indexName);
    }

    // build result tuple header
    _resultTuple = new Tuple();
    AttrType[] resultAttrs = new AttrType[_nOutFlds];
    short[] resultStrSizes = TupleUtils.setup_op_tuple(
        _resultTuple,
        resultAttrs,
        _in1, _len1,
        _in2, _len2,
        _s1, _s2,
        _projList,
        _nOutFlds
    );
    _resultTuple.setHdr((short)_nOutFlds, resultAttrs, resultStrSizes);
  }

  /**
   * Retrieve next joined tuple
   */
  public Tuple get_next() throws IOException, JoinsException {
    try {
      while (true) {
        // if scan open, consume
        if (_btScan != null) {
          KeyDataEntry e = _btScan.get_next();
          if (e != null) {
            RID rid = ((LeafData)e.data).getData();
            Tuple inner = _innerHeap.getRecord(rid);
            inner.setHdr((short)_len2, _in2, _s2);
            if (_useVectorIndex) {
              // filter by true distance
              Vector100Dtype ov = _outerTuple.get100DVectFld(_outerJoinFld);
              Vector100Dtype iv = inner.get100DVectFld(_innerJoinFld);
              double dist = TupleUtils.getDistance(ov, iv);
              if (dist <= _distanceThreshold) {
                Projection.Join(
                  _outerTuple, _in1,
                  inner, _in2,
                  _resultTuple, _projList, _nOutFlds
                );
                return _resultTuple;
              } else {
                continue; // skip, same scan
              }
            } else {
              Projection.Join(
                _outerTuple, _in1,
                inner, _in2,
                _resultTuple, _projList, _nOutFlds
              );
              return _resultTuple;
            }
          }
          _btScan.DestroyBTreeFileScan();
          _btScan = null;
        }

        // advance outer tuple
        _outerTuple = _outer.get_next();
        if (_outerTuple == null) return null;
        _outerTuple.setHdr((short)_len1, _in1, _s1);
        if (_outFilter != null && !PredEval.Eval(_outFilter, _outerTuple, null, _in1, null))
          continue;

        // open new BTree scan
        if (_useVectorIndex) {
          Vector100Dtype v = _outerTuple.get100DVectFld(_outerJoinFld);
          Vector100DKey key = new Vector100DKey(_lshIndex.computeHash(v, _lshIndex.getL(), _lshIndex.getH()));
          IntegerKey convertedKey = LSHF.convertKey(key);

          _btScan = (BTFileScan)_lshTree.new_scan(convertedKey, convertedKey);
        } else {
          int val = _outerTuple.getIntFld(_outerJoinFld);
          _btScan = (BTFileScan)
              _btIndex.new_scan(new IntegerKey(val), new IntegerKey(val));
        }
      }
    } catch (Exception ex) {
      throw new JoinsException(ex, "INLJoins:get_next failed");
    }
  }

  /**
   * Close operator and underlying scans
   */
  public void close() {
    if (_btScan != null) {
      try { _btScan.DestroyBTreeFileScan(); } catch (Exception e) {}
      _btScan = null;
    }
    try { _outer.close(); } catch (Exception e) {}
  }
}
