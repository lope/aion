/*******************************************************************************
 * Copyright (c) 2017-2018 Aion foundation.
 *
 *     This file is part of the aion network project.
 *
 *     The aion network project is free software: you can redistribute it
 *     and/or modify it under the terms of the GNU General Public License
 *     as published by the Free Software Foundation, either version 3 of
 *     the License, or any later version.
 *
 *     The aion network project is distributed in the hope that it will
 *     be useful, but WITHOUT ANY WARRANTY; without even the implied
 *     warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *     See the GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with the aion network project source files.
 *     If not, see <https://www.gnu.org/licenses/>.
 *
 *     The aion network project leverages useful source code from other
 *     open source projects. We greatly appreciate the effort that was
 *     invested in these projects and we thank the individual contributors
 *     for their work. For provenance information and contributors
 *     please see <https://github.com/aionnetwork/aion/wiki/Contributors>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 *     Aion foundation.
 ******************************************************************************/
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
    protected AbstractDB rebuild;
    protected AbstractDB primary;
    protected AbstractDB support;

    protected String NAME_DATA_1 = "data1";
    protected String NAME_DATA_2 = "data2";
    protected String NAME_REBUILD = "rebuild";

    protected byte[] mainDatabaseKey = BigInteger.valueOf(-1L).toByteArray();

    protected AbstractDatabaseWithSplit(String name, String path) {
        super(name, path);
        data = new AbstractDB[2];
    }

    private void setupDatabaseHierarchy() {

        if (rebuild.isEmpty()) {
            rebuild.put(mainDatabaseKey, new byte[]{1});
        }

        Optional<byte[]> current = rebuild.get(mainDatabaseKey);

        if (!current.isPresent()) {
            LOG.error("Missing key");
        } else {
            byte[] currentDb = current.get();

            if (currentDb.length == 0 || currentDb.length > 1) {
                LOG.error("Corrupt <rebuild> database.");
                return;
            }

            if (currentDb[0] == 1) {
                primary = data[0];
                support = data[1];
            } else {
                primary = data[1];
                support = data[0];
            }
        }
    }

    @Override
    public boolean commitCache(Map<ByteArrayWrapper, byte[]> cache) {
        return primary.commitCache(cache);
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
            open =  data[0].open() && data[1].open() && rebuild.open();

            if (open) {
                setupDatabaseHierarchy();
            } else {
                data[0].close();
                data[1].close();
                rebuild.close();
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
            rebuild.close();
            data[0].close();
            data[1].close();
        } finally {
            // ensuring the db is null after close was called
            primary = null;
            support = null;

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

        boolean open = rebuild.isOpen() && data[0].isOpen() && data[1].isOpen();

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
        return rebuild.isPersistent() && data[0].isPersistent() && data[1].isPersistent();
    }

    @Override
    public boolean isCreatedOnDisk() {
        return rebuild.isCreatedOnDisk() && data[0].isCreatedOnDisk() && data[1].isCreatedOnDisk();
    }

    @Override
    public long approximateSize() {
        return rebuild.approximateSize() + data[0].approximateSize() + data[1].approximateSize();
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + ":" + databaseInfo();
    }

    private String databaseInfo() {
        return "<" + data[0].toString() + //
                "," + data[1].toString() + //
                "," + rebuild.toString() + ">";
    }

    // IKeyValueStore functionality ------------------------------------------------------------------------------------

    @Override
    public boolean isEmpty() {
        return rebuild.isEmpty() && data[0].isEmpty() && data[1].isEmpty();
    }

    @Override
    public Set<byte[]> keys() {
        // acquire read lock
        lock.readLock().lock();

        Set<byte[]> set = new HashSet<>();

        try {
            check();

            set.addAll(rebuild.keys());
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
            v = primary.get(k);

            // next check support database
            if (!v.isPresent()) {
                v = support.get(k);
            }

            // finally check rebuild database
            if (!v.isPresent()) {
                v = rebuild.get(k);
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

            primary.put(k, v);
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

            primary.putBatch(inputMap);
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

            primary.deleteBatch(keys);
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }

    // IByteArrayKeyValueDatabase && IByteArrayKeyValueRepository functionality ----------------------------------------

    @Override
    public boolean isRepository() {
        return true;
    }

    @Override
    public void archive(Map<byte[], byte[]> archivedData) {
        // acquire write lock
        lock.writeLock().lock();

        try {
            // stored data used for rebuilding missing info
            rebuild.putBatch(archivedData);
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
            Optional<byte[]> current = rebuild.get(mainDatabaseKey);

            if (!current.isPresent() || current.get()[0] == 2) {
                rebuild.put(mainDatabaseKey, new byte[]{1});

                // set new main database
                primary = data[0];

                // delete previous database contents
                primary.deleteBatch(primary.keys());

                support = data[1];
            } else {
                rebuild.put(mainDatabaseKey, new byte[]{2});

                // set new main database
                primary = data[1];

                // delete previous database contents
                primary.deleteBatch(primary.keys());

                support = data[0];
            }
        } finally {
            // releasing write lock
            lock.writeLock().unlock();
        }
    }

}
