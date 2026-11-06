/*
 * Copyright 2021 Elvis Hew
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.elvishew.xlog.printer.file.backup;

import com.elvishew.xlog.internal.printer.file.backup.BackupStrategyWrapper;
import com.elvishew.xlog.internal.printer.file.backup.BackupUtil;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupTest {

  /** Base modification time used by the tests that need an unambiguous age for every backup. */
  private static final long BASE_TIME = 1_700_000_000_000L;

  String logPath = "build/test/log";

  String logFileName = "log";

  @Before
  public void setup() throws IOException {
    // Clean log folder.
    File folder = new File(logPath);
    File[] files = folder.listFiles();
    if (files != null) {
      for (File file : files) {
        file.delete();
      }
    }

    // Ensure folder exist.
    if (!folder.exists()) {
      folder.mkdirs();
    }
  }

  @Test
  public void testBackupOld() throws Exception {
    BackupStrategy2 backupStrategy = new BackupStrategyWrapper(new BackupStrategy() {
      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    });

    File logFile = new File(logPath, logFileName);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);
  }

  @Test
  public void testBackupMaxIndex1() throws Exception {
    BackupStrategy2 backupStrategy = new AbstractBackupStrategy() {
      @Override
      public int getMaxBackupIndex() {
        return 1;
      }

      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    };

    File logFile = new File(logPath, logFileName);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);
  }

  @Test
  public void testBackupMaxIndex2() throws Exception {
    BackupStrategy2 backupStrategy = new AbstractBackupStrategy() {
      @Override
      public int getMaxBackupIndex() {
        return 2;
      }

      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    };

    File logFile = new File(logPath, logFileName);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(2, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(2, backupStrategy);
  }

  @Test
  public void testBackupMaxIndex5() throws Exception {
    BackupStrategy2 backupStrategy = new AbstractBackupStrategy() {
      @Override
      public int getMaxBackupIndex() {
        return 5;
      }

      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    };

    File logFile = new File(logPath, logFileName);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(2, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(3, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(4, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(5, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(5, backupStrategy);
  }

  /**
   * With a limit, a backup goes to the lowest free index until the max index is in use. From then
   * on, the backup holding the oldest content is overwritten. Note that the index does NOT tell how
   * old a backup is (the backups are a circular buffer).
   */
  @Test
  public void testBackupMaxIndex5WithMissingFile() throws Exception {
    BackupStrategy2 backupStrategy = newBackupStrategy(5);
    File logFile = new File(logPath, logFileName);

    // Content 1..4 go to indexes 1..4.
    for (int seq = 1; seq <= 4; seq++) {
      rotate(logFile, backupStrategy, seq);
    }
    assertFiles(4, backupStrategy);
    assertBackupContent(backupStrategy, 1, 1);
    assertBackupContent(backupStrategy, 2, 2);
    assertBackupContent(backupStrategy, 3, 3);
    assertBackupContent(backupStrategy, 4, 4);

    // Lose the backup in the middle (index 2, content 2).
    new File(logPath, backupStrategy.getBackupFileName(logFileName, 2)).delete();

    // The gap is the lowest free index, so it is filled first, even though it now holds newer
    // content than indexes 3 and 4.
    rotate(logFile, backupStrategy, 5);
    assertFilesCount(4);
    assertBackupContent(backupStrategy, 1, 1);
    assertBackupContent(backupStrategy, 2, 5);
    assertBackupContent(backupStrategy, 3, 3);
    assertBackupContent(backupStrategy, 4, 4);

    // The max index is not in use yet, so the lowest free index (5) is used.
    rotate(logFile, backupStrategy, 6);
    assertFilesCount(5);
    assertBackupContent(backupStrategy, 5, 6);

    // The max index is in use: from now on the oldest content is overwritten (1, then 3, then 4),
    // and the number of files does not grow anymore.
    rotate(logFile, backupStrategy, 7);
    assertFilesCount(5);
    assertBackupContent(backupStrategy, 1, 7);

    rotate(logFile, backupStrategy, 8);
    assertFilesCount(5);
    assertBackupContent(backupStrategy, 3, 8);

    rotate(logFile, backupStrategy, 9);
    assertFilesCount(5);
    assertBackupContent(backupStrategy, 4, 9);

    // Final state: the 5 most recent contents are kept (5..9), in a non chronological index order.
    assertBackupContent(backupStrategy, 1, 7);
    assertBackupContent(backupStrategy, 2, 5);
    assertBackupContent(backupStrategy, 3, 8);
    assertBackupContent(backupStrategy, 4, 9);
    assertBackupContent(backupStrategy, 5, 6);
  }

  /**
   * Whatever the index of each backup is, the backups must always be the most recent contents:
   * once the max index is reached, the one that gets overwritten is always the oldest content.
   */
  @Test
  public void testBackupMaxIndex5AlwaysOverwritesOldestContent() throws Exception {
    BackupStrategy2 backupStrategy = newBackupStrategy(5);
    File logFile = new File(logPath, logFileName);

    for (int seq = 1; seq <= 40; seq++) {
      rotate(logFile, backupStrategy, seq);

      Set<Integer> expected = new TreeSet<Integer>();
      for (int content = Math.max(1, seq - 4); content <= seq; content++) {
        expected.add(content);
      }
      assertEquals("after rotation " + seq, expected,
          backupContents(backupStrategy, logFileName, 5));
    }
  }

  /**
   * The age of the backups must not get mixed up when two backups end up with the same last
   * modified time, or when the clock of the device was set back: the newest backup is always the
   * most recently modified one, so the oldest content is still the one that gets overwritten.
   */
  @Test
  public void testBackupMaxIndex3WithEqualOrEarlierLastModified() throws Exception {
    BackupStrategy2 backupStrategy = newBackupStrategy(3);
    File logFile = new File(logPath, logFileName);

    rotateAt(logFile, backupStrategy, 1, BASE_TIME + 1000);
    rotateAt(logFile, backupStrategy, 2, BASE_TIME + 1000);   // same last modified as content 1
    rotateAt(logFile, backupStrategy, 3, BASE_TIME - 5000);   // the clock was set back
    assertEquals(setOf(1, 2, 3), backupContents(backupStrategy, logFileName, 3));

    // The oldest content is overwritten in order: 1, then 2, then 3...
    rotateAt(logFile, backupStrategy, 4, BASE_TIME + 4000);
    assertEquals(setOf(2, 3, 4), backupContents(backupStrategy, logFileName, 3));
    assertBackupContent(backupStrategy, 1, 4);

    rotateAt(logFile, backupStrategy, 5, BASE_TIME + 5000);
    assertEquals(setOf(3, 4, 5), backupContents(backupStrategy, logFileName, 3));
    assertBackupContent(backupStrategy, 2, 5);

    rotateAt(logFile, backupStrategy, 6, BASE_TIME + 6000);
    assertEquals(setOf(4, 5, 6), backupContents(backupStrategy, logFileName, 3));
    assertBackupContent(backupStrategy, 3, 6);
  }

  /**
   * Backups of other logging files (for example the files of previous days, in the same folder)
   * must never be overwritten, even when they are older than the ones of the current file.
   */
  @Test
  public void testBackupOnlyOverwritesBackupsOfTheSameLogFile() throws Exception {
    BackupStrategy2 backupStrategy = newBackupStrategy(3);
    String yesterdayName = "2026-11-05.log";
    String todayName = "2026-11-06.log";
    File yesterday = new File(logPath, yesterdayName);
    File today = new File(logPath, todayName);

    // Yesterday's backups are full, and older than anything from today.
    for (int seq = 1; seq <= 3; seq++) {
      rotate(yesterday, backupStrategy, seq);
    }

    // Today rotates many more times than the number of backups that fit.
    for (int seq = 101; seq <= 110; seq++) {
      rotate(today, backupStrategy, seq);
    }

    assertFilesCount(6);
    assertEquals(setOf(1, 2, 3), backupContents(backupStrategy, yesterdayName, 3));
    assertEquals(setOf(108, 109, 110), backupContents(backupStrategy, todayName, 3));
  }

  @Test
  public void testBackupMaxIndexNoLimit() throws Exception {
    BackupStrategy2 backupStrategy = new AbstractBackupStrategy() {
      @Override
      public int getMaxBackupIndex() {
        return NO_LIMIT;
      }

      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    };

    File logFile = new File(logPath, logFileName);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(2, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(3, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(4, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(5, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(6, backupStrategy);
  }

  @Test
  public void testBackupMaxIndexNoLimitWithMissingFile() throws Exception {
    BackupStrategy2 backupStrategy = new AbstractBackupStrategy() {
      @Override
      public int getMaxBackupIndex() {
        return NO_LIMIT;
      }

      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    };

    File logFile = new File(logPath, logFileName);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(1, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(2, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(3, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(4, backupStrategy);

    File file2 = new File(logPath, backupStrategy.getBackupFileName(logFileName, 2));
    file2.delete();

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(4, backupStrategy);

    File file1 = new File(logPath, backupStrategy.getBackupFileName(logFileName, 1));
    file1.delete();
    File file3 = new File(logPath, backupStrategy.getBackupFileName(logFileName, 3));
    file3.delete();

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFilesCount(3);
    assertFileExists(backupStrategy, 1);
    assertFileExists(backupStrategy, 2);
    assertFileExists(backupStrategy, 4);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFiles(4, backupStrategy);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFilesCount(5);

    logFile.createNewFile();
    BackupUtil.backup(logFile, backupStrategy);
    assertFilesCount(6);
  }

  private BackupStrategy2 newBackupStrategy(final int maxBackupIndex) {
    return new AbstractBackupStrategy() {
      @Override
      public int getMaxBackupIndex() {
        return maxBackupIndex;
      }

      @Override
      public boolean shouldBackup(File file) {
        return true;
      }
    };
  }

  /**
   * Write {@code seq} as the content of the logging file, give it a strictly increasing
   * modification time (so the age of every content is unambiguous, and the test does not depend on
   * the resolution of the file system clock), and back it up.
   */
  private void rotate(File logFile, BackupStrategy2 backupStrategy, int seq) throws IOException {
    rotateAt(logFile, backupStrategy, seq, BASE_TIME + seq * 1000L);
  }

  /** Same as {@link #rotate}, but with the given last modified time for the logging file. */
  private void rotateAt(File logFile, BackupStrategy2 backupStrategy, int seq, long lastModified)
      throws IOException {
    Files.write(logFile.toPath(), String.valueOf(seq).getBytes(StandardCharsets.UTF_8));
    assertTrue(logFile.setLastModified(lastModified));
    BackupUtil.backup(logFile, backupStrategy);
    assertFalse("The logging file should have been moved", logFile.exists());
  }

  private void assertBackupContent(BackupStrategy2 backupStrategy, int index, int expectedSeq)
      throws IOException {
    File file = new File(logPath, backupStrategy.getBackupFileName(logFileName, index));
    assertTrue("Backup " + index + " should exist", file.exists());
    assertEquals("Content of backup " + index, expectedSeq, readSeq(file));
  }

  /** Contents of the existing backups (indexes 1..max) of the given logging file. */
  private Set<Integer> backupContents(BackupStrategy2 backupStrategy, String fileName, int max)
      throws IOException {
    Set<Integer> contents = new TreeSet<Integer>();
    for (int i = 1; i <= max; i++) {
      File file = new File(logPath, backupStrategy.getBackupFileName(fileName, i));
      if (file.exists()) {
        contents.add(readSeq(file));
      }
    }
    return contents;
  }

  private int readSeq(File file) throws IOException {
    return Integer.parseInt(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
  }

  private Set<Integer> setOf(Integer... values) {
    return new TreeSet<Integer>(Arrays.asList(values));
  }

  private void assertFiles(int fileCount, BackupStrategy2 backupStrategy) {
    assertFilesCount(fileCount);
    for (int i = 1; i <= fileCount; i++) {
      assertFileExists(backupStrategy, i);
    }
  }

  private void assertFilesCount(int filesCount) {
    File folder = new File(logPath);
    File[] files = folder.listFiles();
    assert files != null;
    assertEquals(filesCount, files.length);
  }

  private void assertFileExists(BackupStrategy2 backupStrategy, int index) {
    File file = new File(logPath, backupStrategy.getBackupFileName(logFileName, index));
    assert file.exists();
  }
}