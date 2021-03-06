/*
 * Copyright (c) 2014. Jordan Williams
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jordanwilliams.heftydb.db;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.jordanwilliams.heftydb.compact.Compactor;
import com.jordanwilliams.heftydb.data.Key;
import com.jordanwilliams.heftydb.data.Tuple;
import com.jordanwilliams.heftydb.metrics.Metrics;
import com.jordanwilliams.heftydb.read.TableReader;
import com.jordanwilliams.heftydb.state.Caches;
import com.jordanwilliams.heftydb.state.Paths;
import com.jordanwilliams.heftydb.state.Snapshots;
import com.jordanwilliams.heftydb.state.Tables;
import com.jordanwilliams.heftydb.util.CloseableIterator;
import com.jordanwilliams.heftydb.write.TableWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

/**
 * The main database API implementation
 */
public class HeftyDB implements DB {

    private class InstrumentedScanIterator implements CloseableIterator<Record> {

        private final CloseableIterator<Record> delegate;

        private InstrumentedScanIterator(CloseableIterator<Record> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            Timer.Context watch = scanTimer.time();
            boolean hasNext = delegate.hasNext();
            watch.stop();
            return hasNext;
        }

        @Override
        public Record next() {
            Record record = delegate.next();
            scanRate.mark(record.key().capacity() + record.value().capacity());
            return record;
        }

        @Override
        public void remove() {
            delegate.remove();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private final TableWriter tableWriter;
    private final TableReader tableReader;
    private final Compactor compactor;
    private final Snapshots snapshots;
    private final Metrics metrics;

    private final Timer writeTimer;
    private final Timer readTimer;
    private final Timer scanTimer;
    private final Meter writeRate;
    private final Meter readRate;
    private final Meter scanRate;

    private HeftyDB(Config config, Paths paths, Tables tables, Snapshots snapshots, Caches caches, Metrics metrics) {
        this.snapshots = snapshots;
        this.tableWriter = new TableWriter(config, paths, tables, snapshots, caches, metrics);
        this.tableReader = new TableReader(tables, metrics);
        this.compactor = new Compactor(config, paths, tables, caches, config.compactionStrategy(), metrics, snapshots);
        this.metrics = metrics;

        this.writeTimer = metrics.timer("write");
        this.readTimer = metrics.timer("read");
        this.scanTimer = metrics.timer("scan");
        this.writeRate = metrics.meter("write.rate");
        this.readRate = metrics.meter("read.rate");
        this.scanRate = metrics.meter("scan.rate");
    }

    @Override
    public Snapshot put(ByteBuffer key, ByteBuffer value) throws IOException {
        return write(key, value, false);
    }

    @Override
    public Snapshot put(ByteBuffer key, ByteBuffer value, boolean fsync) throws IOException {
        return write(key, value, fsync);
    }

    @Override
    public Record get(ByteBuffer key) throws IOException {
        return read(key, snapshots.currentId());
    }

    @Override
    public Record get(ByteBuffer key, Snapshot snapshot) throws IOException {
        return read(key, snapshot.id());
    }

    @Override
    public Snapshot delete(ByteBuffer key) throws IOException {
        return write(key, null, false);
    }

    @Override
    public CloseableIterator<Record> ascendingIterator(Snapshot snapshot) throws IOException {
        return new InstrumentedScanIterator(new Record.TupleIterator(tableReader.ascendingIterator(snapshot.id())));
    }

    @Override
    public CloseableIterator<Record> ascendingIterator(ByteBuffer key, Snapshot snapshot) throws IOException {
        return new InstrumentedScanIterator(new Record.TupleIterator(tableReader.ascendingIterator(new Key(key,
                snapshot.id()), snapshot.id())));
    }

    @Override
    public CloseableIterator<Record> descendingIterator(Snapshot snapshot) throws IOException {
        return new InstrumentedScanIterator(new Record.TupleIterator(tableReader.descendingIterator(snapshot.id())));
    }

    @Override
    public CloseableIterator<Record> descendingIterator(ByteBuffer key, Snapshot snapshot) throws IOException {
        return new InstrumentedScanIterator(new Record.TupleIterator(tableReader.descendingIterator(new Key(key,
                snapshot.id()), snapshot.id())));
    }

    @Override
    public void retainSnapshot(Snapshot snapshot) {
        snapshots.retain(snapshot.id());
    }

    @Override
    public void releaseSnapshot(Snapshot snapshot) {
        snapshots.release(snapshot.id());
    }

    @Override
    public synchronized void close() throws IOException {
        metrics.close();
        compactor.close();
        tableWriter.close();
        tableReader.close();
    }

    @Override
    public void logMetrics() {
        metrics.logMetrics();
    }

    @Override
    public synchronized Future<?> compact() throws IOException {
        return compactor.scheduleCompaction();
    }

    private Snapshot write(ByteBuffer key, ByteBuffer value, boolean fsync) throws IOException {
        Timer.Context watch = writeTimer.time();
        Snapshot snapshot = tableWriter.write(key, value, fsync);
        watch.stop();
        int valueCapacity = value == null ? 0 : value.capacity();
        writeRate.mark(key.capacity() + valueCapacity);
        return snapshot;
    }

    private Record read(ByteBuffer key, long snapshotId) {
        Timer.Context watch = readTimer.time();
        Tuple tuple = tableReader.get(new Key(key, snapshotId));
        watch.stop();
        if (tuple != null) {
            readRate.mark(tuple.size());
        }
        return tuple == null || tuple.value().isEmpty() ? null : new Record(tuple);
    }

    public static DB open(Config config) throws IOException {
        Metrics metrics = new Metrics(config);
        DBState state = new DBInitializer(config, metrics).initialize();
        return new HeftyDB(state.config(), state.paths(), state.tables(), state.snapshots(), state.caches(), metrics);
    }
}
