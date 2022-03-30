/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.std.*;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class TableReaderMetadataTest extends AbstractCairoTest {

    private volatile Throwable exception = null;

    @Before
    public void setUp2() {
        CairoTestUtils.createAllTable(configuration, PartitionBy.DAY);
    }

    @Test
    public void testAddColumn() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "str:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n" +
                "xyz:STRING\n";
        assertThat(expected, 12, (w) -> w.addColumn("xyz", ColumnType.STRING));
    }

    @Test
    public void testAddColumnConcurrent() throws Throwable {
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger columnsAdded = new AtomicInteger();
        AtomicInteger reloadCount = new AtomicInteger();
        int totalColAddCount = 1000;

        Thread writerThread = new Thread(() -> {
            try (TableWriter writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, "all", "test")) {
                start.await();
                for (int i = 0; i < totalColAddCount; i++) {
                    writer.addColumn("col" + i, ColumnType.INT);
                    columnsAdded.incrementAndGet();
                }
            } catch (Throwable e) {
                exception = e;
                LOG.error().$(e).$();
            } finally {
                done.incrementAndGet();
            }
        });

        Thread readerThread = new Thread(() -> {
            try (TableReader reader = engine.getReader(AllowAllCairoSecurityContext.INSTANCE, "all")) {
                start.await();
                int colAdded = -1, newColsAdded;
                while (colAdded < totalColAddCount) {
                    if (colAdded < (newColsAdded = columnsAdded.get())) {
                        reader.reload();
                        colAdded = newColsAdded;
                        reloadCount.incrementAndGet();
                    }
                    Os.pause();
                }
            } catch (Throwable e) {
                exception = e;
                LOG.error().$(e).$();
            }
        });
        writerThread.start();
        readerThread.start();

        writerThread.join();
        readerThread.join();

        if (exception != null) {
            throw exception;
        }
        Assert.assertTrue(reloadCount.get() > 100);
        LOG.infoW().$("total reload count ").$(reloadCount.get()).$();
    }

    @Test
    public void testAddRemoveAddRemove() throws Exception {
        final String expected = "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "str:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n" +
                "int:INT\n";

        assertThat(expected, 11,
                w -> w.addColumn("bin2", ColumnType.BINARY),
                w -> w.removeColumn("bin2"),
                w -> w.removeColumn("int"),
                w -> w.addColumn("int", ColumnType.INT)
        );
    }

    @Test
    public void testColumnIndex() {
        ObjIntHashMap<String> expected = new ObjIntHashMap<>();
        expected.put("int", 0);
        expected.put("byte", 2);
        expected.put("bin", 9);
        expected.put("short", 1);
        expected.put("float", 4);
        expected.put("long", 5);
        expected.put("xyz", -1);
        expected.put("str", 6);
        expected.put("double", 3);
        expected.put("sym", 7);
        expected.put("bool", 8);

        expected.put("zall.sym", -1);

        try (Path path = new Path().of(root).concat("all").concat(TableUtils.META_FILE_NAME).$();
             TableReaderMetadata metadata = new TableReaderMetadata(FilesFacadeImpl.INSTANCE, path)) {
            for (ObjIntHashMap.Entry<String> e : expected) {
                Assert.assertEquals(e.value, metadata.getColumnIndexQuiet(e.key));
            }
        }
    }

    @Test
    public void testDeleteTwoAddOneColumn() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n" +
                "xyz:STRING\n";
        assertThat(expected, 10, (w) -> {
            w.removeColumn("double");
            w.removeColumn("str");
            w.addColumn("xyz", ColumnType.STRING);
        });
    }

    @Test
    public void testFreeNullAddressAsIndex() {
        TableUtils.freeTransitionIndex(0);
    }

    @Test
    public void testRemoveAllColumns() throws Exception {
        final String expected = "";
        assertThat(expected, 0, (w) -> {
            w.removeColumn("int");
            w.removeColumn("short");
            w.removeColumn("byte");
            w.removeColumn("float");
            w.removeColumn("long");
            w.removeColumn("str");
            w.removeColumn("sym");
            w.removeColumn("bool");
            w.removeColumn("bin");
            w.removeColumn("date");
            w.removeColumn("double");
        });
    }

    @Test
    public void testRemoveAndAddSameColumn() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n" +
                "str:STRING\n";
        assertThat(expected, 11,
                w -> w.removeColumn("str"),
                w -> w.addColumn("str", ColumnType.STRING)
        );
    }

    @Test
    public void testRemoveColumnAndReAdd() throws Exception {
        final String expected = "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n" +
                "str:STRING\n" +
                "short:INT\n";

        assertThat(expected, 10,
                w -> w.removeColumn("short"),
                w -> w.removeColumn("str"),
                w -> w.removeColumn("int"),
                w -> w.addColumn("str", ColumnType.STRING),
                // change column type
                w -> w.addColumn("short", ColumnType.INT)
        );
    }

    @Test
    public void testRemoveDenseColumns() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "long:LONG\n" +
                "str:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n";
        assertThat(expected, 9,
                w -> w.removeColumn("double"),
                w -> w.removeColumn("float")
        );
    }

    @Test
    public void testRemoveFirstAndLastColumns() throws Exception {
        final String expected = "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "str:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n";
        assertThat(expected, 9,
                w -> w.removeColumn("date"),
                w -> w.removeColumn("int")
        );
    }

    @Test
    public void testRemoveFirstColumn() throws Exception {
        final String expected =
                "short:SHORT\n" +
                        "byte:BYTE\n" +
                        "double:DOUBLE\n" +
                        "float:FLOAT\n" +
                        "long:LONG\n" +
                        "str:STRING\n" +
                        "sym:SYMBOL\n" +
                        "bool:BOOLEAN\n" +
                        "bin:BINARY\n" +
                        "date:DATE\n";
        assertThat(expected, 10, (w) -> w.removeColumn("int"));
    }

    @Test
    public void testRemoveLastColumn() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "str:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n";
        assertThat(expected, 10, (w) -> w.removeColumn("date"));
    }

    @Test
    public void testRemoveRandomColumns() throws Exception {
        Rnd rnd = TestUtils.generateRandom();
        final String allColumns = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "str:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n";

        List<String> lines = new ArrayList<>(Arrays.asList(allColumns.split("\n")));

        while (lines.size() > 0) {
            int removeIndex = rnd.nextInt() % lines.size();
            if (removeIndex >= 0 && removeIndex < lines.size()) {
                String line = lines.get(removeIndex);
                String name = line.substring(0, line.indexOf(':'));

                lines.remove(removeIndex);
                String expected = String.join("\n", lines);
                if (lines.size() > 0) {
                    expected += "\n";
                }

                runWithManipulators(expected, lines.size(), w -> w.removeColumn(name));
            }
        }
    }

    @Test
    public void testRemoveSparseColumns() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n";

        assertThat(expected, 9,
                w -> w.removeColumn("double"),
                w -> w.removeColumn("str"));
    }

    @Test
    public void testRenameColumn() throws Exception {
        final String expected = "int:INT\n" +
                "short:SHORT\n" +
                "byte:BYTE\n" +
                "double:DOUBLE\n" +
                "float:FLOAT\n" +
                "long:LONG\n" +
                "str1:STRING\n" +
                "sym:SYMBOL\n" +
                "bool:BOOLEAN\n" +
                "bin:BINARY\n" +
                "date:DATE\n";
        assertThat(expected, 11, (w) -> w.renameColumn("str", "str1"));
    }

    private void assertThat(String expected, int columnCount, ColumnManipulator... manipulators) throws Exception {
        // Test one by one
        runWithManipulators(expected, columnCount, manipulators);
        try (Path path = new Path()) {
            engine.remove(AllowAllCairoSecurityContext.INSTANCE, path, "all");
        }
        CairoTestUtils.createAllTable(configuration, PartitionBy.DAY);

        // Test in one go
        runWithManipulators(expected, columnCount, w -> {
            for (ColumnManipulator manipulator : manipulators) {
                manipulator.restructure(w);
            }
        });
    }

    private void runWithManipulators(String expected, int columnCount, ColumnManipulator... manipulators) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = new Path().of(root).concat("all")) {
                int tableId;
                try (TableReaderMetadata metadata = new TableReaderMetadata(FilesFacadeImpl.INSTANCE, path.concat(TableUtils.META_FILE_NAME).$())) {
                    tableId = metadata.getId();
                    for (ColumnManipulator manipulator : manipulators) {
                        long structVersion;
                        try (TableWriter writer = new TableWriter(configuration, "all", metrics)) {
                            manipulator.restructure(writer);
                            structVersion = writer.getStructureVersion();
                        }
                        long pTransitionIndex = metadata.createTransitionIndex(structVersion);
                        try {
                            metadata.applyTransitionIndex();
                        } finally {
                            TableUtils.freeTransitionIndex(pTransitionIndex);
                        }
                    }
                    StringSink sink = new StringSink();
                    for (int i = 0; i < metadata.getColumnCount(); i++) {
                        sink.put(metadata.getColumnName(i)).put(':').put(ColumnType.nameOf(metadata.getColumnType(i))).put('\n');
                    }

                    TestUtils.assertEquals(expected, sink);

                    if (expected.length() > 0) {
                        String[] lines = expected.split("\n");
                        Assert.assertEquals(lines.length, metadata.columnCount);

                        for (int i = 0; i < lines.length; i++) {
                            int p = lines[i].indexOf(':');
                            Assert.assertEquals(i, metadata.getColumnIndexQuiet(lines[i].substring(0, p)));
                        }
                    }
                }

                // Check that table has same tableId.
                try (TableReaderMetadata metadata = new TableReaderMetadata(FilesFacadeImpl.INSTANCE, path.concat(TableUtils.META_FILE_NAME).$())) {
                    Assert.assertEquals(tableId, metadata.getId());
                }
            }
        });
    }

    @FunctionalInterface
    public interface ColumnManipulator {
        void restructure(TableWriter writer);
    }
}