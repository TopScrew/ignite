/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.indexing.h2.opt;


import org.gridgain.grid.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.util.offheap.unsafe.*;
import org.h2.api.*;
import org.h2.command.ddl.*;
import org.h2.engine.*;
import org.h2.index.*;
import org.h2.message.*;
import org.h2.result.*;
import org.h2.schema.*;
import org.h2.table.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * H2 Table implementation.
 */
public class GridH2Table extends TableBase {
    /** */
    private final String spaceName;

    /** */
    private final GridH2RowDescriptor desc;

    /** */
    private final ArrayList<Index> idxs;

    /** */
    private final ReadWriteLock lock;

    /** */
    private final boolean manyUniqueIdxs;

    /** */
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap8<Session, Boolean>());

    /** */
    private volatile Object[] actualSnapshot;

    /** */
    private final long writeLockWaitTime;

    /**
     * Creates table.
     *
     * @param createTblData Table description.
     * @param desc Row descriptor.
     * @param idxsFactory Indexes factory.
     * @param spaceName Space name.
     */
    public GridH2Table(CreateTableData createTblData, @Nullable GridH2RowDescriptor desc, IndexesFactory idxsFactory,
        @Nullable String spaceName) {
        super(createTblData);

        assert idxsFactory != null;

        this.desc = desc;
        this.spaceName = spaceName;

        writeLockWaitTime = desc == null ? 100 : desc.spi().getIndexWriteLockWaitTime();

        assert writeLockWaitTime > 0 : writeLockWaitTime;

        idxs = idxsFactory.createIndexes(this);

        assert idxs != null;
        assert idxs.size() >= 1;

        lock =  new ReentrantReadWriteLock();

        if (idxs.size() > 1) {
            int uniqueIndexesCnt = 0;

            for (Index idx : idxs) {
                if (idx.getIndexType().isUnique())
                    uniqueIndexesCnt++;
            }

            assert uniqueIndexesCnt > 0;

            // Because one of them is PK which can't cause conflicts.
            manyUniqueIdxs = uniqueIndexesCnt > 2;
        }
        else
            manyUniqueIdxs = false;

        // Add scan index at 0 which is required by H2.
        idxs.add(0, new ScanIndex(index(0)));
    }

    /** {@inheritDoc} */
    @Override public long getDiskSpaceUsed() {
        return 0;
    }

    /**
     * @return Row descriptor.
     */
    public GridH2RowDescriptor rowDescriptor() {
        return desc;
    }

    /**
     * Should be called when entry is swapped.
     *
     * @param key Entry key.
     * @return {@code true} If row was found.
     * @throws GridSpiException If failed.
     */
    public boolean onSwap(Object key) throws GridException {
        return onSwapUnswap(key, null);
    }

    /**
     * Should be called when entry is unswapped.
     *
     * @param key Key.
     * @param val Value.
     * @return {@code true} If row was found.
     * @throws GridSpiException If failed.
     */
    public boolean onUnswap(Object key, Object val) throws GridException {
        assert val != null : "Key=" + key;

        return onSwapUnswap(key, val);
    }

    /**
     * Swaps or unswaps row.
     *
     * @param key Key.
     * @param val Value for promote or {@code null} if we have to swap.
     * @return {@code true} if row was found and swapped/unswapped.
     * @throws GridException If failed.
     */
    @SuppressWarnings("LockAcquiredButNotSafelyReleased")
    private boolean onSwapUnswap(Object key, @Nullable Object val) throws GridException {
        assert key != null;

        GridH2TreeIndex pk = pk();

        GridH2AbstractKeyValueRow row = desc.createRow(key, null, 0); // Create search row.

        GridUnsafeMemory mem = desc.memory();

        lock.readLock().lock();

        GridUnsafeMemory.Operation op = mem == null ? null : mem.begin(); // Begin concurrent unsafe memory operation.

        try {
            row = pk.findOne(row);

            if (row == null)
                return false;

            if (val == null)
                row.onSwap();
            else
                row.onUnswap(val);

            return true;
        }
        finally {
            lock.readLock().unlock();

            if (mem != null)
                mem.end(op);
        }
    }

    /**
     * @return Space name.
     */
    @Nullable String spaceName() {
        return spaceName;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"LockAcquiredButNotSafelyReleased", "SynchronizationOnLocalVariableOrMethodParameter", "unchecked"})
    @Override public void lock(@Nullable final Session ses, boolean exclusive, boolean force) {
        if (ses != null) {
            if (!sessions.add(ses))
                return;

            synchronized (ses) {
                ses.addLock(this);
            }
        }

        Object[] snapshot;

        for (long waitTime = writeLockWaitTime;; waitTime *= 2) { // Increase wait time to avoid starvation.
            snapshot = actualSnapshot;

            if (snapshot != null) {
                // Reuse existing snapshot without locking.
                for (int i = 1, len = idxs.size(); i < len; i++)
                    index(i).takeSnapshot(snapshot[i - 1]);

                return;
            }

            try {
                if (lock.writeLock().tryLock(waitTime, TimeUnit.MILLISECONDS))
                    break;
            }
            catch (InterruptedException e) {
                throw new GridRuntimeException("Thread got interrupted while trying to acquire index lock.", e);
            }
        }

        boolean snapshoted = false;

        try {
            snapshot = actualSnapshot; // Try again inside of the lock.

            if (snapshot == null) {
                snapshot = takeIndexesSnapshot();

                if (desc == null || desc.memory() == null) // This optimization is disabled for off-heap index.
                    actualSnapshot = snapshot;

                snapshoted = true;
            }
        }
        finally {
            lock.writeLock().unlock();
        }

        if (!snapshoted) {
            for (int i = 1, len = idxs.size(); i < len; i++)
                index(i).takeSnapshot(snapshot[i - 1]);
        }
    }

    /**
     * Must be called inside of write lock because when using multiple indexes we have to ensure that all of them have
     * the same contents at snapshot taking time.
     *
     * @return New indexes data snapshot.
     */
    @SuppressWarnings("unchecked")
    private Object[] takeIndexesSnapshot() {
        int len = idxs.size();

        Object[] snapshot = new ConcurrentNavigableMap[len - 1];

        for (int i = 1; i < len; i++) { // Take snapshots on all except first which is scan.
            Object s = index(i).takeSnapshot(null);

            snapshot[i - 1] = s;
        }

        return snapshot;
    }

    /** {@inheritDoc} */
    @Override public void close(Session ses) {
        assert !sessions.contains(ses);
    }

    /** {@inheritDoc} */
    @Override public void unlock(@Nullable Session ses) {
        if (ses != null) {
            boolean res = sessions.remove(ses);

            assert res;
        }

        for (int i = 1, len = idxs.size(); i < len; i++)  // Release snapshots on all except first which is scan.
            index(i).releaseSnapshot();
    }

    /**
     * Closes table and releases resources.
     */
    public void close() {
        Lock l = lock.writeLock();

        l.lock();

        try {
            for (int i = 1, len = idxs.size(); i < len; i++)
                index(i).close(null);
        }
        finally {
            l.unlock();
        }
    }

    /**
     * Updates table for given key. If value is null then row with given key will be removed from table,
     * otherwise value and expiration time will be updated or new row will be added.
     *
     * @param key Key.
     * @param val Value.
     * @param expirationTime Expiration time.
     * @return {@code True} if operation succeeded.
     * @throws GridSpiException If failed.
     */
    public boolean update(Object key, @Nullable Object val, long expirationTime) throws GridSpiException {
        GridH2Row row = desc.createRow(key, val, expirationTime);

        return doUpdate(row, val == null);
    }

    /**
     * Gets index by index.
     *
     * @param idx Index in list.
     * @return Index.
     */
    private GridH2IndexBase index(int idx) {
        return (GridH2IndexBase)idxs.get(idx);
    }

    /**
     * Gets primary key.
     *
     * @return Primary key.
     */
    private GridH2TreeIndex pk() {
        return (GridH2TreeIndex)idxs.get(1);
    }

    /**
     * For testing only.
     *
     * @param row Row.
     * @param del If given row should be deleted from table.
     * @return {@code True} if operation succeeded.
     * @throws GridSpiException If failed.
     */
    @SuppressWarnings("LockAcquiredButNotSafelyReleased")
    boolean doUpdate(GridH2Row row, boolean del) throws GridSpiException {
        // Here we assume that each key can't be updated concurrently and case when different indexes
        // getting updated from different threads with different rows with the same key is impossible.
        GridUnsafeMemory mem = desc == null ? null : desc.memory();

        lock.readLock().lock();

        GridUnsafeMemory.Operation op = null;

        if (mem != null)
            op = mem.begin();

        try {
            GridH2TreeIndex pk = pk();

            if (!del) {
                GridH2Row old = pk.put(row, false); // Put to PK.

                // In which indexes row was added and in which it was replaced.
                BitSet replaced = null;

                int len = idxs.size();

                if (old != null) {
                    replaced = new BitSet(len);

                    replaced.set(1);
                }

                int i = 1;

                try {
                    // Put row if absent to all indexes sequentially.
                    // Start from 2 because 0 - Scan (don't need to update), 1 - PK (already updated).
                    while (++i < len) {
                        GridH2IndexBase idx = index(i);

                        // For non-unique index we just do put.
                        boolean ifAbsent = idx.getIndexType().isUnique();

                        GridH2Row old2 = idx.put(row, ifAbsent);

                        if (old2 != null) {
                            if (eq(pk, old2, old)) {
                                // Can safely replace since operations on single cache key
                                // which is PK in table can't be concurrent.
                                if (ifAbsent) {
                                    old2 = idx.put(row, false);

                                    assert eq(pk, old2, old);
                                }

                                replaced.set(i);

                                continue;
                            }

                            assert ifAbsent : "\n" + row + "\n" + old + "\n" + old2;

                            // Check if old2 is concurrently inserting row which can fail on some unique index.
                            if (manyUniqueIdxs && !old2.waitInsertComplete()) {
                                // Try again.
                                i--;

                                continue;
                            }

                            break; // Unique index violation.
                        }
                    }

                    if (i == len) { // New row was added to all indexes, can remove old row where it was not replaced.
                        if (old != null) {
                            for (int j = 2; j < len; j++) {
                                if (!replaced.get(j)) {
                                    Row res =  index(j).remove(old);

                                    assert eq(pk, res, old) : j + ") " + index(j) + "\n" + old + "\n" + res;
                                }
                            }
                        }
                    }
                    else { // Not all indexes were updated, rollback to previous state and throw exception.
                        for (int j = 1; j < i; j++) {
                            // If replaced replace back, if added remove.
                            Row res = replaced != null && replaced.get(j) ?
                                index(j).put(old, false) : index(j).remove(row);

                            assert eq(pk, res, row) : j + ") " + index(j) + "\n" + old + "\n" + res + "\n" + row;
                        }

                        throw new GridSpiException("Failed to update index [index=" + index(i) + ", row=" + row + "]");
                    }
                }
                finally {
                    row.finishInsert(i == len);
                }
            }
            else {
                //  index(1) is PK, get full row from there (search row here contains only key but no other columns).
                row = pk.remove(row);

                if (row != null) {
                    // Remove row from all indexes.
                    // Start from 2 because 0 - Scan (don't need to update), 1 - PK (already updated).
                    for (int i = 2, len = idxs.size(); i < len; i++) {
                        Row res = index(i).remove(row);

                        assert eq(pk, res, row): "\n" + row + "\n" + res;
                    }
                }
                else
                    return false;
            }

            // The snapshot is not actual after update.
            actualSnapshot = null;

            return true;
        }
        finally {
            lock.readLock().unlock();

            if (mem != null)
                mem.end(op);
        }
    }

    /**
     * Check row equality.
     *
     * @param pk Primary key index.
     * @param r1 First row.
     * @param r2 Second row.
     * @return {@code true} if rows are the same.
     */
    private static boolean eq(Index pk, SearchRow r1, SearchRow r2) {
        return r1 == r2 || (r1 != null && r2 != null && pk.compareRows(r1, r2) == 0);
    }

    /**
     * For testing only.
     *
     * @return Indexes.
     */
    ArrayList<GridH2IndexBase> indexes() {
        ArrayList<GridH2IndexBase> res = new ArrayList<>(idxs.size() - 1);

        for (int i = 1, len = idxs.size(); i < len ; i++)
            res.add(index(i));

        return res;
    }

    /**
     * Rebuilds all indexes of this table.
     */
    public void rebuildIndexes() {
        GridUnsafeMemory memory = desc == null ? null : desc.memory();

        lock.writeLock().lock();

        try {
            if (memory == null && actualSnapshot == null)
                actualSnapshot = takeIndexesSnapshot(); // Allow read access while we are rebuilding indexes.

            for (int i = 1, len = idxs.size(); i < len; i++) {
                GridH2IndexBase newIdx = index(i).rebuild(memory);

                idxs.set(i, newIdx);

                if (i == 1) // ScanIndex at 0 and actualSnapshot can contain references to old indexes, reset them.
                    idxs.set(0, new ScanIndex(newIdx));
            }
        }
        catch (InterruptedException ignored) {
            // No-op.
        }
        finally {
            lock.writeLock().unlock();

            actualSnapshot = null;
        }
    }

    /** {@inheritDoc} */
    @Override public Index addIndex(Session ses, String s, int i, IndexColumn[] idxCols, IndexType idxType,
        boolean b, String s1) {
        throw DbException.getUnsupportedException("addIndex");
    }

    /** {@inheritDoc} */
    @Override public void removeRow(Session ses, Row row) {
        throw DbException.getUnsupportedException("removeRow");
    }

    /** {@inheritDoc} */
    @Override public void truncate(Session ses) {
        throw DbException.getUnsupportedException("truncate");
    }

    /** {@inheritDoc} */
    @Override public void addRow(Session ses, Row row) {
        throw DbException.getUnsupportedException("addRow");
    }

    /** {@inheritDoc} */
    @Override public void checkSupportAlter() {
        throw DbException.getUnsupportedException("alter");
    }

    /** {@inheritDoc} */
    @Override public String getTableType() {
        return EXTERNAL_TABLE_ENGINE;
    }

    /** {@inheritDoc} */
    @Override public Index getScanIndex(Session ses) {
        return getIndexes().get(0); // Scan must be always first index.
    }

    /** {@inheritDoc} */
    @Override public Index getUniqueIndex() {
        return getIndexes().get(1); // PK index is always second.
    }

    /** {@inheritDoc} */
    @Override public ArrayList<Index> getIndexes() {
        return idxs;
    }

    /** {@inheritDoc} */
    @Override public boolean isLockedExclusively() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isLockedExclusivelyBy(Session ses) {
        return false;
    }

    /** {@inheritDoc} */
    @Override public long getMaxDataModificationId() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override public boolean isDeterministic() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean canGetRowCount() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean canDrop() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public long getRowCount(@Nullable Session ses) {
        return getUniqueIndex().getRowCount(ses);
    }

    /** {@inheritDoc} */
    @Override public long getRowCountApproximation() {
        return getUniqueIndex().getRowCountApproximation();
    }

    /** {@inheritDoc} */
    @Override public void checkRename() {
        throw DbException.getUnsupportedException("rename");
    }

    /**
     * Creates index column for table.
     *
     * @param col Column index.
     * @param sorting Sorting order {@link SortOrder}
     * @return Created index column.
     */
    public IndexColumn indexColumn(int col, int sorting) {
        IndexColumn res = new IndexColumn();

        res.column = getColumn(col);
        res.columnName = res.column.getName();
        res.sortType = sorting;

        return res;
    }

    /**
     * H2 Table engine.
     */
    @SuppressWarnings({"PublicInnerClass", "FieldAccessedSynchronizedAndUnsynchronized"})
    public static class Engine implements TableEngine {
        /** */
        private static GridH2RowDescriptor rowDesc;

        /** */
        private static IndexesFactory idxsFactory;

        /** */
        private static GridH2Table resTbl;

        /** */
        private static String spaceName;

        /** {@inheritDoc} */
        @Override public TableBase createTable(CreateTableData createTblData) {
            resTbl = new GridH2Table(createTblData, rowDesc, idxsFactory, spaceName);

            return resTbl;
        }

        /**
         * Creates table using given connection, DDL clause for given type descriptor and list of indexes.
         *
         * @param conn Connection.
         * @param sql DDL clause.
         * @param desc Row descriptor.
         * @param factory Indexes factory.
         * @param space Space name.
         * @throws SQLException If failed.
         * @return Created table.
         */
        public static synchronized GridH2Table createTable(Connection conn, String sql,
            @Nullable GridH2RowDescriptor desc, IndexesFactory factory, String space)
            throws SQLException {
            rowDesc = desc;
            idxsFactory = factory;
            spaceName = space;

            try {

                try (Statement s = conn.createStatement()) {
                    s.execute(sql + " engine \"" + Engine.class.getName() + "\"");
                }

                return resTbl;
            }
            finally {
                resTbl = null;
                idxsFactory = null;
                rowDesc = null;
            }
        }
    }

    /**
     * Type which can create indexes list for given table.
     */
    @SuppressWarnings({"PackageVisibleInnerClass", "PublicInnerClass"})
    public static interface IndexesFactory {
        /**
         * Create list of indexes. First must be primary key, after that all unique indexes and
         * only then non-unique indexes.
         * All indexes must be subtypes of {@link GridH2TreeIndex}.
         *
         * @param tbl Table to create indexes for.
         * @return List of indexes.
         */
        ArrayList<Index> createIndexes(GridH2Table tbl);
    }

    /**
     * Wrapper type for primary key.
     */
    @SuppressWarnings("PackageVisibleInnerClass")
    static class ScanIndex implements Index {
        /** */
        static final String SCAN_INDEX_NAME_SUFFIX = "__SCAN_";

        /** */
        private static final IndexType TYPE = IndexType.createScan(false);

        /** */
        private final GridH2IndexBase delegate;

        /**
         * Constructor.
         *
         * @param delegate Index delegate to.
         */
        private ScanIndex(GridH2IndexBase delegate) {
            this.delegate = delegate;
        }

        /** {@inheritDoc} */
        @Override public long getDiskSpaceUsed() {
            return 0;
        }

        /** {@inheritDoc} */
        @Override public void add(Session ses, Row row) {
            delegate.add(ses, row);
        }

        /** {@inheritDoc} */
        @Override public boolean canFindNext() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public boolean canGetFirstOrLast() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public boolean canScan() {
            return delegate.canScan();
        }

        /** {@inheritDoc} */
        @Override public void close(Session ses) {
            delegate.close(ses);
        }

        /** {@inheritDoc} */
        @Override public void commit(int operation, Row row) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public int compareRows(SearchRow rowData, SearchRow compare) {
            return delegate.compareRows(rowData, compare);
        }

        /** {@inheritDoc} */
        @Override public Cursor find(TableFilter filter, SearchRow first, SearchRow last) {
            return find(filter.getSession(), first, last);
        }

        /** {@inheritDoc} */
        @Override public Cursor find(Session ses, SearchRow first, SearchRow last) {
            return delegate.find(ses, null, null);
        }

        /** {@inheritDoc} */
        @Override public Cursor findFirstOrLast(Session ses, boolean first) {
            throw DbException.getUnsupportedException("SCAN");
        }

        /** {@inheritDoc} */
        @Override public Cursor findNext(Session ses, SearchRow higherThan, SearchRow last) {
            throw DbException.throwInternalError();
        }

        /** {@inheritDoc} */
        @Override public int getColumnIndex(Column col) {
            return -1;
        }

        /** {@inheritDoc} */
        @Override public Column[] getColumns() {
            return delegate.getColumns();
        }

        /** {@inheritDoc} */
        @Override public double getCost(Session ses, int[] masks, TableFilter tblFilter, SortOrder sortOrder) {
            return getRowCountApproximation() + Constants.COST_ROW_OFFSET;
        }

        /** {@inheritDoc} */
        @Override public IndexColumn[] getIndexColumns() {
            return delegate.getIndexColumns();
        }

        /** {@inheritDoc} */
        @Override public IndexType getIndexType() {
            return TYPE;
        }

        /** {@inheritDoc} */
        @Override public String getPlanSQL() {
            return delegate.getTable().getSQL() + "." + SCAN_INDEX_NAME_SUFFIX;
        }

        /** {@inheritDoc} */
        @Override public Row getRow(Session ses, long key) {
            return delegate.getRow(ses, key);
        }

        /** {@inheritDoc} */
        @Override public long getRowCount(Session ses) {
            return delegate.getRowCount(ses);
        }

        /** {@inheritDoc} */
        @Override public long getRowCountApproximation() {
            return delegate.getRowCountApproximation();
        }

        /** {@inheritDoc} */
        @Override public Table getTable() {
            return delegate.getTable();
        }

        /** {@inheritDoc} */
        @Override public boolean isRowIdIndex() {
            return delegate.isRowIdIndex();
        }

        /** {@inheritDoc} */
        @Override public boolean needRebuild() {
            return false;
        }

        /** {@inheritDoc} */
        @Override public void remove(Session ses) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void remove(Session ses, Row row) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void setSortedInsertMode(boolean sortedInsertMode) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void truncate(Session ses) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public Schema getSchema() {
            return delegate.getSchema();
        }

        /** {@inheritDoc} */
        @Override public boolean isHidden() {
            return delegate.isHidden();
        }

        /** {@inheritDoc} */
        @Override public void checkRename() {
            throw DbException.getUnsupportedException("rename");
        }

        /** {@inheritDoc} */
        @Override public ArrayList<DbObject> getChildren() {
            return delegate.getChildren();
        }

        /** {@inheritDoc} */
        @Override public String getComment() {
            return delegate.getComment();
        }

        /** {@inheritDoc} */
        @Override public String getCreateSQL() {
            return null; // Scan should return null.
        }

        /** {@inheritDoc} */
        @Override public String getCreateSQLForCopy(Table tbl, String quotedName) {
            return delegate.getCreateSQLForCopy(tbl, quotedName);
        }

        /** {@inheritDoc} */
        @Override public Database getDatabase() {
            return delegate.getDatabase();
        }

        /** {@inheritDoc} */
        @Override public String getDropSQL() {
            return delegate.getDropSQL();
        }

        /** {@inheritDoc} */
        @Override public int getId() {
            return delegate.getId();
        }

        /** {@inheritDoc} */
        @Override public String getName() {
            return delegate.getName() + SCAN_INDEX_NAME_SUFFIX;
        }

        /** {@inheritDoc} */
        @Override public String getSQL() {
            return delegate.getSQL();
        }

        /** {@inheritDoc} */
        @Override public int getType() {
            return delegate.getType();
        }

        /** {@inheritDoc} */
        @Override public boolean isTemporary() {
            return delegate.isTemporary();
        }

        /** {@inheritDoc} */
        @Override public void removeChildrenAndResources(Session ses) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public void rename(String newName) {
            throw DbException.getUnsupportedException("rename");
        }

        /** {@inheritDoc} */
        @Override public void setComment(String comment) {
            throw DbException.getUnsupportedException("comment");
        }

        /** {@inheritDoc} */
        @Override public void setTemporary(boolean temporary) {
            throw DbException.getUnsupportedException("temporary");
        }
    }
}
