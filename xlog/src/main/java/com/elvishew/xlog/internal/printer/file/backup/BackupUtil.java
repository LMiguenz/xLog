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

package com.elvishew.xlog.internal.printer.file.backup;

import com.elvishew.xlog.printer.file.backup.BackupStrategy2;

import java.io.File;

public class BackupUtil {

  /**
   * Backup the logging file. The backup is saved with the lowest free index, until maxBackupIndex
   * is in use. After that, the existing backup of the same logging file with the oldest content
   * (the least recently modified one) is overwritten. Existing backups are never renamed.
   *
   * @param loggingFile    the logging file
   * @param backupStrategy the strategy should be use when backing up
   */
  public static void backup(File loggingFile, BackupStrategy2 backupStrategy) {
    String loggingFileName = loggingFile.getName();
    String path = loggingFile.getParent();
    File backupFile;
    File nextBackupFile;
    int maxBackupIndex = backupStrategy.getMaxBackupIndex();
    if (maxBackupIndex > 0) {
      backupFile = new File(path, backupStrategy.getBackupFileName(loggingFileName, maxBackupIndex));
      File target = null;
      if (backupFile.exists()) {
        // maxBackupIndex is already being used, overwrite the oldest file
        File oldestBackupFile = findOldestBackupFile(path, loggingFileName, backupStrategy, maxBackupIndex);
        target = oldestBackupFile != null ? oldestBackupFile : backupFile;
      } else {
        // maxBackupIndex not reached yet; use the lowest available index for the backup file
        for (int i = 1; i <= maxBackupIndex; i++) {
          nextBackupFile = new File(path, backupStrategy.getBackupFileName(loggingFileName, i));
          if (!nextBackupFile.exists()) {
            target = nextBackupFile;
            break;
          }
        }
      }
      if (target != null && loggingFile.renameTo(target)) {
        markAsNewestBackup(target, path, loggingFileName, backupStrategy, maxBackupIndex);
      }
    } else if (maxBackupIndex == BackupStrategy2.NO_LIMIT) {
      for (int i = 1; i < Integer.MAX_VALUE; i++) {
        nextBackupFile = new File(path, backupStrategy.getBackupFileName(loggingFileName, i));
        if (!nextBackupFile.exists()) {
          loggingFile.renameTo(nextBackupFile);
          break;
        }
      }
    } else {
      // Illegal maxBackIndex, could not come here.
    }
  }

  /**
   * Make sure that the backup that has just been created is the most recently modified one among
   * the backups of its logging file.
   * <p>
   * The backup holds the newest content, and the last modified time is what tells the oldest backup
   * apart when recycling. Renaming keeps the last modified time that the logging file had, which
   * could be earlier than (or the same as) the one of an existing backup, if the clock of the device
   * was set back, or if its resolution is not enough to tell apart two consecutive backups. In that
   * case, set it just after the newest existing backup, so the age of the backups never gets mixed
   * up.
   */
  private static void markAsNewestBackup(File newBackup, String dirPath, String loggingFileName,
                                         BackupStrategy2 backupStrategy, int max) {
    long newestOther = Long.MIN_VALUE;
    for (int i = 1; i <= max; i++) {
      File other = new File(dirPath, backupStrategy.getBackupFileName(loggingFileName, i));
      if (other.equals(newBackup) || !other.isFile()) continue;

      newestOther = Math.max(newestOther, other.lastModified());
    }
    if (newestOther != Long.MIN_VALUE && newBackup.lastModified() <= newestOther) {
      newBackup.setLastModified(newestOther + 1);
    }
  }

  /**
   * Find the oldest (least recently modified) existing backup of the given logging file.
   * <p>
   * Only the backups that belong to {@code loggingFileName} are considered, i.e. the files named by
   * {@code backupStrategy.getBackupFileName(loggingFileName, i)} for {@code i} in
   * {@code 1..max}. Other files in the same directory (such as the backups of another day's log
   * file) are never touched.
   *
   * @return the oldest existing backup file, or null if none of the candidates exists
   */
  private static File findOldestBackupFile(String dirPath, String loggingFileName,
                                           BackupStrategy2 backupStrategy, int max) {
    File oldestFile = null;
    for (int i = 1; i <= max; i++) {
      File candidate = new File(dirPath, backupStrategy.getBackupFileName(loggingFileName, i));
      if (!candidate.isFile()) continue;

      if (oldestFile == null || candidate.lastModified() < oldestFile.lastModified()) {
        oldestFile = candidate;
      }
    }
    return oldestFile;
  }


  /**
   * Check if a {@link BackupStrategy2} is valid, will throw a exception if invalid.
   *
   * @param backupStrategy the backup strategy to be verify
   */
  public static void verifyBackupStrategy(BackupStrategy2 backupStrategy) {
    int maxBackupIndex = backupStrategy.getMaxBackupIndex();
    if (maxBackupIndex < 0) {
      throw new IllegalArgumentException("Max backup index should not be less than 0");
    } else if (maxBackupIndex == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Max backup index too big: " + maxBackupIndex);
    }
  }
}
