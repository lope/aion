package org.aion.db.impl;

import org.aion.base.db.IByteArrayKeyValueRepository;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.util.*;

public class AbstractDatabaseWithSplit extends AbstractDB implements IByteArrayKeyValueRepository {
    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.DB.name());

    protected AbstractDB[] data;
    protected AbstractDB rebuildDB;
    protected AbstractDB mainDB;
    protected AbstractDB supportDB;

    protected String NAME_DATA_1 = "data1";
    protected String NAME_DATA_2 = "data2";
    protected String NAME_REBUILD = "rebuild";

    protected byte[] mainDatabaseKey = BigInteger.valueOf(-1L).toByteArray();

    protected AbstractDatabaseWithSplit(String name, String path) {
        super(name, path);
        data = new AbstractDB[2];
    }

    private void setupDatabaseHierarchy() {

        if (rebuildDB.isEmpty()) {
            rebuildDB.put(mainDatabaseKey, new byte[]{1});
        }

        Optional<byte[]> current = rebuildDB.get(mainDatabaseKey);

        if (!current.isPresent()) {
            LOG.error("Missing key");
        } else {
            byte[] currentDb = current.get();

            if (currentDb.length == 0 || currentDb.length > 1) {
                LOG.error("Corrupt <rebuild> database.");
                return;
            }

            if (currentDb[0] == 1) {
                mainDB = data[0];
                supportDB = data[1];
            } else {
                mainDB = data[1];
                supportDB = data[0];
            }
        }
    }

    @Override
    public boolean commitCache(Map<ByteArrayWrapper, byte[]> cache) {
        return mainDB.commitCache(cache);
    }

    // IDatabase functionality -----------------------------------------------------------------------------------------

    /**
     * @inheritDoc
     */
    @Override
    public boolean open() {
        // acquire write lock
        lock.writeLock().lock();

        if (isOpen()) {
            // releasing write lock and return status
            lock.writeLock().unlock();
            return true;
        }

        boolean open;

        try {
            LOG.debug("init split database {}", this.toString());

            // correctly open only when all 3 components are open
            open = rebuildDB.open() && data[0].open() && data[1].open();

            if (open) {
                setupDatabaseHierarchy();
            }
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }

        return open;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void close() {
        // acquire write lock
        lock.writeLock().lock();

        try {
            // close databases
            rebuildDB.close();
            data[0].close();
            data[1].close();
        } finally {
            // ensuring the db is null after close was called
            mainDB = null;
            supportDB = null;

            // releasing write lock
            lock.writeLock().unlock();
        }
    }

    @Override
    public void archive(Map<byte[], byte[]> archivedData) {
        // acquire write lock
        lock.writeLock().lock();

        try {
            // stored data used for rebuilding missing info
            rebuildDB.putBatch(archivedData);
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }

    @Override
    public void swap() {
        // acquire write lock
        lock.writeLock().lock();

        try {
            Optional<byte[]> current = rebuildDB.get(mainDatabaseKey);

            if (!current.isPresent() || current.get()[0] == 2) {
                rebuildDB.put(mainDatabaseKey, new byte[]{1});

                // set new main database
                mainDB = data[0];

                // delete previous database contents
                mainDB.deleteBatch(mainDB.keys());

                supportDB = data[1];
            } else {
                rebuildDB.put(mainDatabaseKey, new byte[]{2});

                // set new main database
                mainDB = data[1];

                // delete previous database contents
                mainDB.deleteBatch(mainDB.keys());

                supportDB = data[0];
            }
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public boolean isOpen() {
        // acquire read lock
        lock.readLock().lock();

        boolean open = rebuildDB.isOpen() && data[0].isOpen() && data[1].isOpen();

        // releasing read lock
        lock.readLock().unlock();

        return open;
    }

    @Override
    public boolean isAutoCommitEnabled() {
        // this implementation requires explicit commits
        return false;
    }

    @Override
    public boolean isPersistent() {
        return rebuildDB.isPersistent() && data[0].isPersistent() && data[1].isPersistent();
    }

    @Override
    public boolean isCreatedOnDisk() {
        return rebuildDB.isCreatedOnDisk() && data[0].isCreatedOnDisk() && data[1].isCreatedOnDisk();
    }

    @Override
    public long approximateSize() {
        return rebuildDB.approximateSize() + data[0].approximateSize() + data[1].approximateSize();
    }

    // IKeyValueStore functionality ------------------------------------------------------------------------------------

    @Override
    public boolean isEmpty() {
        return rebuildDB.isEmpty() && data[0].isEmpty() && data[1].isEmpty();
    }

    @Override
    public Set<byte[]> keys() {
        // acquire read lock
        lock.readLock().lock();

        Set<byte[]> set = new HashSet<>();

        try {
            check();

            set.addAll(rebuildDB.keys());
            set.addAll(data[0].keys());
            set.addAll(data[1].keys());
        } finally {
            // releasing read lock
            lock.readLock().unlock();
        }

        // empty when retrieval failed
        return set;
    }

    @Override
    public Optional<byte[]> get(byte[] k) {
        AbstractDB.check(k);

        // acquire read lock
        lock.readLock().lock();

        Optional<byte[]> v;

        try {
            // this runtime exception should not be caught here
            check();

            // first check main database
            v = mainDB.get(k);

            // next check support database
            if (!v.isPresent()) {
                v = supportDB.get(k);
            }

            // finally check rebuild database
            if (!v.isPresent()) {
                v = rebuildDB.get(k);
            }

        } finally {
            // releasing read lock
            lock.readLock().unlock();
        }

        return v;
    }

    @Override
    public void put(byte[] k, byte[] v) {
        AbstractDB.check(k);

        // acquire write lock
        lock.writeLock().lock();

        try {
            check();

            mainDB.put(k, v);
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(byte[] k) {
        put(k, null);
    }

    @Override
    public void putBatch(Map<byte[], byte[]> inputMap) {
        AbstractDB.check(inputMap.keySet());

        // acquire write lock
        lock.writeLock().lock();

        try {
            check();

            mainDB.putBatch(inputMap);
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }

    @Override
    public void deleteBatch(Collection<byte[]> keys) {
        AbstractDB.check(keys);

        // acquire write lock
        lock.writeLock().lock();

        try {
            check();

            mainDB.deleteBatch(keys);
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }


}
