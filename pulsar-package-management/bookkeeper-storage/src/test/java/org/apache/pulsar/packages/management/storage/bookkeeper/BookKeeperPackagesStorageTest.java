/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.packages.management.storage.bookkeeper;

import org.apache.distributedlog.exceptions.LogNotFoundException;
import org.apache.distributedlog.exceptions.ZKException;
import org.apache.pulsar.packages.management.core.PackagesStorage;
import org.apache.pulsar.packages.management.core.PackagesStorageProvider;
import org.apache.pulsar.packages.management.core.impl.DefaultPackagesStorageConfiguration;
import org.apache.pulsar.packages.management.storage.bookkeeper.bookkeeper.test.BookKeeperClusterTestCase;
import org.junit.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class BookKeeperPackagesStorageTest extends BookKeeperClusterTestCase {
    private PackagesStorage storage;

    public BookKeeperPackagesStorageTest() {
        super(1);
    }

    @BeforeMethod()
    public void setup() throws Exception {
        PackagesStorageProvider provider = PackagesStorageProvider
            .newProvider(BookKeeperPackagesStorageProvider.class.getName());
        DefaultPackagesStorageConfiguration configuration = new DefaultPackagesStorageConfiguration();
        configuration.setProperty("zkServers", zkUtil.getZooKeeperConnectString());
        configuration.setProperty("numReplicas", "1");
        configuration.setProperty("ledgerRootPath", "/ledgers");
        storage = provider.getStorage(configuration);
        storage.initialize();
    }

    @AfterMethod
    public void teardown() throws Exception {
        if (storage != null) {
            storage.closeAsync().get();
        }
    }

    @Test(timeOut = 60000)
    public void testConfiguration() {
        assertTrue(storage instanceof BookKeeperPackagesStorage);
        BookKeeperPackagesStorage bkStorage = (BookKeeperPackagesStorage) storage;
        assertEquals(bkStorage.configuration.getZkServers(), zkUtil.getZooKeeperConnectString());
        assertEquals(bkStorage.configuration.getNumReplicas(), 1);
        assertEquals(bkStorage.configuration.getLedgersRootPath(), "/ledgers");
    }

    @Test(timeOut = 60000)
    public void testReadWriteOperations() throws ExecutionException, InterruptedException {
        String testData = "test-data";
        ByteArrayInputStream testDataStream = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));
        String testPath = "test-read-write";

        // write some data to the dlog
        storage.writeAsync(testPath, testDataStream).get();

        // read the data from the dlog
        ByteArrayOutputStream readData = new ByteArrayOutputStream();
        storage.readAsync(testPath, readData).get();
        String readResult = new String(readData.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(readResult.equals(testData));
    }

    @Test(timeOut = 60000)
    public void testReadNonExistentData() {
        String testPath = "non-existent-path";
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            storage.readAsync(testPath, outputStream).get();
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof LogNotFoundException);
        }
    }

    @Test(timeOut = 60000)
    public void testListOperation() throws ExecutionException, InterruptedException {
        // write the data to different path
        String rootPath = "pulsar";
        String testData = "test-data";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));

        List<String> writePaths = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String path = "test-" + i;
            writePaths.add(path);
            storage.writeAsync(rootPath + "/" + path, inputStream).get();
        }

        // list all path under the root path
        List<String> paths = storage.listAsync(rootPath).get();

        // verify the paths number
        Assert.assertEquals(writePaths.size(), paths.size());
        paths.forEach(p -> writePaths.remove(p));
        Assert.assertEquals(0, writePaths.size());

        // list non-existent path
        try {
            storage.listAsync("non-existent").get();
        } catch (Exception e) {
            // should not throw any exception
            fail(e.getMessage());
        }
    }

    @Test(timeOut = 60000)
    public void testDeleteOperation() throws ExecutionException, InterruptedException {
        String testPath = "test-delete-path";
        String testData = "test-data";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(testData.getBytes(StandardCharsets.UTF_8));

        // write the data to the test path
        storage.writeAsync(testPath, inputStream).get();

        // list path should have one file
        List<String> paths = storage.listAsync("").get();
        Assert.assertEquals(1, paths.size());
        Assert.assertEquals(testPath, paths.get(0));

        // delete the path
        storage.deleteAsync(testPath).get();

        // list again and not file under the path
        paths= storage.listAsync("").get();
        Assert.assertEquals(0, paths.size());


        // delete non-existent path
        try {
            storage.deleteAsync("non-existent").get();
            fail("should throw exception");
        } catch (Exception e) {
            Assert.assertTrue(e.getCause() instanceof ZKException);
        }
    }

    @Test(timeOut = 60000)
    public void testExistOperation() throws ExecutionException, InterruptedException {
        Boolean exist = storage.existAsync("test-path").get();
        assertFalse(exist);

        storage.writeAsync("test-path", new ByteArrayInputStream("test".getBytes())).get();

        exist = storage.existAsync("test-path").get();
        Assert.assertTrue(exist);
    }

}
