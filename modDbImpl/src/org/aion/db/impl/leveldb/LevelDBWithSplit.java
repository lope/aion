package org.aion.db.impl.leveldb;

import org.aion.db.impl.AbstractDatabaseWithSplit;

public class LevelDBWithSplit extends AbstractDatabaseWithSplit {

    public LevelDBWithSplit(String name, String path, boolean enableCache, boolean enableCompression) {
        // main database setup
        super(name, path);

        // the underlying databases
        data[0] = new LevelDB(NAME_DATA_1, this.path, enableCache, enableCompression);
        data[1] = new LevelDB(NAME_DATA_2, this.path, enableCache, enableCompression);
        rebuildDB = new LevelDB(NAME_REBUILD, this.path, enableCache, enableCompression);
    }
}
