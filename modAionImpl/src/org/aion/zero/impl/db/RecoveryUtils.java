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
 * Contributors:
 *     Aion foundation.
 ******************************************************************************/
package org.aion.zero.impl.db;

import org.aion.base.db.IByteArrayKeyValueDatabase;
import org.aion.base.type.IBlock;
import org.aion.base.util.ByteArrayWrapper;
import org.aion.db.impl.DatabaseFactory;
import org.aion.log.AionLoggerFactory;
import org.aion.mcf.db.IBlockStoreBase;
import org.aion.zero.impl.AionBlockchainImpl;
import org.aion.zero.impl.config.CfgAion;
import org.aion.zero.impl.types.AionBlock;

import java.util.*;

public class RecoveryUtils {

    public enum Status {
        SUCCESS, FAILURE, ILLEGAL_ARGUMENT
    }

    /**
     * Used by the CLI call.
     */
    public static Status revertTo(long nbBlock) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        Map<String, String> cfgLog = new HashMap<>();
        cfgLog.put("DB", "INFO");
        cfgLog.put("GEN", "INFO");

        AionLoggerFactory.init(cfgLog);

        // get the current blockchain
        AionBlockchainImpl blockchain = AionBlockchainImpl.inst();

        Status status = revertTo(blockchain, nbBlock);

        blockchain.getRepository().close();

        // ok if we managed to get down to the expected block
        return status;
    }

    /**
     * Used by the CLI call.
     */
    public static void pruneAndCorrect() {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        cfg.getDb().setHeapCacheEnabled(false);

        Map<String, String> cfgLog = new HashMap<>();
        cfgLog.put("DB", "INFO");
        cfgLog.put("GEN", "INFO");

        AionLoggerFactory.init(cfgLog);

        // get the current blockchain
        AionBlockchainImpl blockchain = AionBlockchainImpl.inst();

        IBlockStoreBase store = blockchain.getBlockStore();

        IBlock bestBlock = store.getBestBlock();
        if (bestBlock == null) {
            System.out.println("Empty database. Nothing to do.");
            return;
        }

        // revert to block number and flush changes
        store.pruneAndCorrect();
        store.flush();

        // compact database after the changes were applied
        blockchain.getRepository().compact();

        blockchain.getRepository().close();
    }

    /**
     * Used by internal world state recovery method.
     */
    public static Status revertTo(AionBlockchainImpl blockchain, long nbBlock) {
        IBlockStoreBase store = blockchain.getBlockStore();

        IBlock bestBlock = store.getBestBlock();
        if (bestBlock == null) {
            System.out.println("Empty database. Nothing to do.");
            return Status.ILLEGAL_ARGUMENT;
        }

        long nbBestBlock = bestBlock.getNumber();

        System.out.println("Attempting to revert best block from " + nbBestBlock + " to " + nbBlock + " ...");

        // exit with warning if the given block is larger or negative
        if (nbBlock < 0) {
            System.out.println(
                    "Negative values <" + nbBlock + "> cannot be interpreted as block numbers. Nothing to do.");
            return Status.ILLEGAL_ARGUMENT;
        }
        if (nbBestBlock == 0) {
            System.out.println("Only genesis block in database. Nothing to do.");
            return Status.ILLEGAL_ARGUMENT;
        }
        if (nbBlock == nbBestBlock) {
            System.out.println(
                    "The block " + nbBlock + " is the current best block stored in the database. Nothing to do.");
            return Status.ILLEGAL_ARGUMENT;
        }
        if (nbBlock > nbBestBlock) {
            System.out.println("The block #" + nbBlock + " is greater than the current best block #" + nbBestBlock
                    + " stored in the database. "
                    + "Cannot move to that block without synchronizing with peers. Start Aion instance to sync.");
            return Status.ILLEGAL_ARGUMENT;
        }

        // revert to block number and flush changes
        store.revert(nbBlock);
        store.flush();

        nbBestBlock = store.getBestBlock().getNumber();

        // ok if we managed to get down to the expected block
        return (nbBestBlock == nbBlock) ? Status.SUCCESS : Status.FAILURE;
    }

    public static void archiveState(int blockNumber) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        Map<String, String> cfgLog = new HashMap<>();
        cfgLog.put("DB", "INFO");

        AionLoggerFactory.init(cfgLog);

        // get the current blockchain
        AionRepositoryImpl repository = AionRepositoryImpl.inst();

        AionBlockStore store = repository.getBlockStore();

        long topBlock = store.getMaxNumber();
        Set<ByteArrayWrapper> usefulKeys = new HashSet<>();
        long targetBlock = blockNumber;
        if (targetBlock < 0) {
            targetBlock = 0;
        }
        if (targetBlock > topBlock) {
            targetBlock = topBlock - 1;
        }

        System.out.println("Creating swap database.");
        Properties props = new Properties();
        props.setProperty("db_type", cfg.getDb().getVendor());
        props.setProperty("db_name", "swap");
        props.setProperty("db_path", cfg.getDb().getPath());
        props.setProperty("enable_auto_commit", "true");
        props.setProperty("enable_db_cache", "true");
        props.setProperty("enable_db_compression", "true");
        props.setProperty("enable_heap_cache", "false");
        props.setProperty("max_fd_alloc_size", String.valueOf(cfg.getDb().getFdOpenAllocSize()));
        props.setProperty("block_size", String.valueOf(cfg.getDb().getBlockSize()));
        props.setProperty("write_buffer_size", String.valueOf(cfg.getDb().getWriteBufferSize()));
        props.setProperty("cache_size", String.valueOf(cfg.getDb().getCacheSize()));

        IByteArrayKeyValueDatabase swapDB = DatabaseFactory.connect(props);

        // open the database connection
        swapDB.open();

        // check object status
        if (swapDB == null) {
            System.out.println("Swap database connection could not be established.");
        }

        // check persistence status
        if (!swapDB.isCreatedOnDisk()) {
            System.out.println("Sawp database cannot be saved to disk.");
        }

        // trace full state for bottom block to swap database
        System.out.println("Getting full state for " + targetBlock);
        AionBlock block = store.getChainBlockByNumber(targetBlock);
        byte[] stateRoot = block.getStateRoot();
        repository.getWorldState().saveFullStateToDatabase(stateRoot, swapDB);

        while (targetBlock < topBlock) {
            targetBlock++;
            System.out.println("Getting diff state for " + targetBlock);
            block = store.getChainBlockByNumber(targetBlock);
            stateRoot = block.getStateRoot();
            repository.getWorldState().saveDiffStateToDatabase(stateRoot, swapDB);
        }

        //        topBlock = blockNumber - 1;
        //        targetBlock = 1;
        //        while (targetBlock <= topBlock) {
        //            System.out.println("Deleting diff state for " + targetBlock);
        //            block = store.getChainBlockByNumber(targetBlock);
        //            stateRoot = block.getStateRoot();
        //            repository.getWorldState().deleteDiffStateToDatabase(stateRoot, swapDB);
        //            targetBlock++;
        //        }

        repository.getWorldState().pruneAllExcept(swapDB);
        swapDB.close();
        repository.close();
    }

    public static void getStateSize(int blockNumber) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        Map<String, String> cfgLog = new HashMap<>();
        cfgLog.put("DB", "INFO");

        AionLoggerFactory.init(cfgLog);

        // get the current blockchain
        AionRepositoryImpl repository = AionRepositoryImpl.inst();

        AionBlockStore store = repository.getBlockStore();

        long topBlock = store.getMaxNumber();
        Set<ByteArrayWrapper> usefulKeys = new HashSet<>();
        long targetBlock = blockNumber;
        if (targetBlock < 0) {
            targetBlock = 0;
        }
        if (targetBlock > topBlock) {
            targetBlock = topBlock - 1;
        }

        AionBlock block;
        byte[] stateRoot;

        while (targetBlock <= topBlock) {
            block = store.getChainBlockByNumber(targetBlock);
            stateRoot = block.getStateRoot();
            System.out.println("Block number = " + targetBlock + ", tx count = " + block.getTransactionsList().size()
                    + ", state trie kv count = " + repository.getWorldState().printFullStateSize(stateRoot));
            targetBlock++;
        }

        repository.close();
    }

    public static void printTrieState(int blockNumber) {
        // ensure mining is disabled
        CfgAion cfg = CfgAion.inst();
        cfg.dbFromXML();
        cfg.getConsensus().setMining(false);

        Map<String, String> cfgLog = new HashMap<>();
        cfgLog.put("DB", "INFO");

        AionLoggerFactory.init(cfgLog);

        // get the current blockchain
        AionRepositoryImpl repository = AionRepositoryImpl.inst();

        AionBlockStore store = repository.getBlockStore();

        AionBlock block = store.getChainBlockByNumber(blockNumber);
        byte[] stateRoot = block.getStateRoot();
        System.out.println("Block number = " + blockNumber + ", tx count = " + block.getTransactionsList().size() + "\n"
                + repository.getWorldState().printFullState(stateRoot));

        repository.close();
    }
}
