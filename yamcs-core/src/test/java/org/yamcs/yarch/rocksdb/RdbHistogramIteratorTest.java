package org.yamcs.yarch.rocksdb;

import static org.junit.Assert.*;

import org.junit.Test;
import org.yamcs.utils.TimeInterval;
import org.yamcs.yarch.HistogramIterator;
import org.yamcs.yarch.TableDefinition;
import org.yamcs.yarch.TableWriter;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchTestCase;
import org.yamcs.yarch.TableWriter.InsertMode;

public class RdbHistogramIteratorTest  extends YarchTestCase {
    long t0 = 0L;
    long t1 = 100000000000L;
    
    @Test
    public void test1() throws Exception {
        TableDefinition tblDef = populate();
        RdbStorageEngine rse =  RdbStorageEngine.getInstance();
        TimeInterval interval = new TimeInterval();

        HistogramIterator iter = rse.getHistogramIterator(ydb, tblDef, "name", interval);
        assertNumElementsEqual(iter, 3);
        assertFalse(iter.hasNext());
        iter.close();
        
        iter = rse.getHistogramIterator(ydb, tblDef, "name", interval);
        iter.seek("p1".getBytes(), t0+2000L);           
        assertNumElementsEqual(iter, 2);
        iter.close();
        
        
        HistogramIterator iter1 = rse.getHistogramIterator(ydb, tblDef, "name", interval);
        iter1.seek("p1".getBytes(), t1+1L);   
        
        assertNumElementsEqual(iter1, 0);
        iter1.close();
        
    }
    
    public TableDefinition populate() throws Exception {
        String query="create table table1(gentime timestamp, seqNum int, name string, primary key(gentime, seqNum)) histogram(name) "
                + "partition by time(gentime) table_format=compressed engine rocksdb2";
        execute(query);
        

        TableDefinition tblDef= ydb.getTable("table1");
        RdbStorageEngine    rse = (RdbStorageEngine) ydb.getStorageEngine(tblDef);
        TableWriter tw = rse.newTableWriter(ydb, tblDef, InsertMode.INSERT);
        tw.onTuple(null, new Tuple(tblDef.getTupleDefinition(), new Object[]{t0+1000L, 1, "p1"}));
        tw.onTuple(null, new Tuple(tblDef.getTupleDefinition(), new Object[]{t0+2000L, 2, "p1"}));        
        tw.onTuple(null, new Tuple(tblDef.getTupleDefinition(), new Object[]{t0+40000L, 3, "p1"}));
        tw.onTuple(null, new Tuple(tblDef.getTupleDefinition(), new Object[]{t0+40001L, 4, "p1"}));
        tw.onTuple(null, new Tuple(tblDef.getTupleDefinition(), new Object[]{t1, 4, "p1"}));
        
        tw.close();
        return tblDef;
    }
}
