/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.core.datastore.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;

import org.apache.carbondata.common.logging.LogService;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.FileHolder;
import org.apache.carbondata.core.datastore.filesystem.AlluxioCarbonFile;
import org.apache.carbondata.core.datastore.filesystem.CarbonFile;
import org.apache.carbondata.core.datastore.filesystem.HDFSCarbonFile;
import org.apache.carbondata.core.datastore.filesystem.LocalCarbonFile;
import org.apache.carbondata.core.datastore.filesystem.ViewFSCarbonFile;
import org.apache.carbondata.core.util.CarbonUtil;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.BZip2Codec;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.GzipCodec;

public final class FileFactory {
  /**
   * LOGGER
   */
  private static final LogService LOGGER =
      LogServiceFactory.getLogService(FileFactory.class.getName());
  private static Configuration configuration = null;

  static {
    configuration = new Configuration();
    configuration.addResource(new Path("../core-default.xml"));
  }

  private FileFactory() {

  }

  public static Configuration getConfiguration() {
    return configuration;
  }

  public static FileHolder getFileHolder(FileType fileType) {
    switch (fileType) {
      case LOCAL:
        return new FileHolderImpl();
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        return new DFSFileHolderImpl();
      default:
        return new FileHolderImpl();
    }
  }

  public static FileType getFileType(String path) {
    String lowerPath = path.toLowerCase();
    if (lowerPath.startsWith(CarbonCommonConstants.HDFSURL_PREFIX)) {
      return FileType.HDFS;
    } else if (lowerPath.startsWith(CarbonCommonConstants.ALLUXIOURL_PREFIX)) {
      return FileType.ALLUXIO;
    } else if (lowerPath.startsWith(CarbonCommonConstants.VIEWFSURL_PREFIX)) {
      return FileType.VIEWFS;
    } else if (lowerPath.startsWith(CarbonCommonConstants.S3N_PREFIX) ||
        lowerPath.startsWith(CarbonCommonConstants.S3A_PREFIX) ||
        lowerPath.startsWith(CarbonCommonConstants.S3_PREFIX)) {
      return FileType.S3;
    }
    return FileType.LOCAL;
  }

  public static CarbonFile getCarbonFile(String path) {
    return getCarbonFile(path, getFileType(path));
  }

  public static CarbonFile getCarbonFile(String path, FileType fileType) {
    switch (fileType) {
      case LOCAL:
        return new LocalCarbonFile(getUpdatedFilePath(path, fileType));
      case HDFS:
      case S3:
        return new HDFSCarbonFile(path);
      case ALLUXIO:
        return new AlluxioCarbonFile(path);
      case VIEWFS:
        return new ViewFSCarbonFile(path);
      default:
        return new LocalCarbonFile(getUpdatedFilePath(path, fileType));
    }
  }

  public static CarbonFile getCarbonFile(String path, FileType fileType,
      Configuration hadoopConf) {
    switch (fileType) {
      case LOCAL:
        return new LocalCarbonFile(getUpdatedFilePath(path, fileType));
      case HDFS:
      case S3:
        return new HDFSCarbonFile(path, hadoopConf);
      case ALLUXIO:
        return new AlluxioCarbonFile(path);
      case VIEWFS:
        return new ViewFSCarbonFile(path);
      default:
        return new LocalCarbonFile(getUpdatedFilePath(path, fileType));
    }
  }

  public static DataInputStream getDataInputStream(String path, FileType fileType)
      throws IOException {
    return getDataInputStream(path, fileType, -1);
  }

  public static DataInputStream getDataInputStream(String path, FileType fileType, int bufferSize)
      throws IOException {
    return getDataInputStream(path, fileType, bufferSize, configuration);
  }

  public static DataInputStream getDataInputStream(String path, FileType fileType, int bufferSize,
      Configuration hadoopConf)
      throws IOException {
    path = path.replace("\\", "/");
    boolean gzip = path.endsWith(".gz");
    boolean bzip2 = path.endsWith(".bz2");
    InputStream stream;
    switch (fileType) {
      case LOCAL:
        path = getUpdatedFilePath(path, fileType);
        if (gzip) {
          stream = new GZIPInputStream(new FileInputStream(path));
        } else if (bzip2) {
          stream = new BZip2CompressorInputStream(new FileInputStream(path));
        } else {
          stream = new FileInputStream(path);
        }
        break;
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path pt = new Path(path);
        FileSystem fs = pt.getFileSystem(hadoopConf);
        if (bufferSize == -1) {
          stream = fs.open(pt);
        } else {
          stream = fs.open(pt, bufferSize);
        }
        String codecName = null;
        if (gzip) {
          codecName = GzipCodec.class.getName();
        } else if (bzip2) {
          codecName = BZip2Codec.class.getName();
        }
        if (null != codecName) {
          CompressionCodecFactory ccf = new CompressionCodecFactory(hadoopConf);
          CompressionCodec codec = ccf.getCodecByClassName(codecName);
          stream = codec.createInputStream(stream);
        }
        break;
      default:
        throw new UnsupportedOperationException("unsupported file system");
    }
    return new DataInputStream(new BufferedInputStream(stream));
  }

  /**
   * return the datainputStream which is seek to the offset of file
   *
   * @param path
   * @param fileType
   * @param bufferSize
   * @param offset
   * @return DataInputStream
   * @throws IOException
   */
  public static DataInputStream getDataInputStream(String path, FileType fileType, int bufferSize,
      long offset) throws IOException {
    path = path.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path pt = new Path(path);
        FileSystem fs = pt.getFileSystem(configuration);
        FSDataInputStream stream = fs.open(pt, bufferSize);
        stream.seek(offset);
        return new DataInputStream(new BufferedInputStream(stream));
      default:
        path = getUpdatedFilePath(path, fileType);
        FileInputStream fis = new FileInputStream(path);
        long actualSkipSize = 0;
        long skipSize = offset;
        while (actualSkipSize != offset) {
          actualSkipSize += fis.skip(skipSize);
          skipSize = skipSize - actualSkipSize;
        }
        return new DataInputStream(new BufferedInputStream(fis));
    }
  }

  public static DataOutputStream getDataOutputStream(String path, FileType fileType)
      throws IOException {
    path = path.replace("\\", "/");
    switch (fileType) {
      case LOCAL:
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path pt = new Path(path);
        FileSystem fs = pt.getFileSystem(configuration);
        return fs.create(pt, true);
      default:
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
    }
  }

  public static DataOutputStream getDataOutputStream(String path, FileType fileType, int bufferSize,
      boolean append) throws IOException {
    path = path.replace("\\", "/");
    switch (fileType) {
      case LOCAL:
        path = getUpdatedFilePath(path, fileType);
        return new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(path, append), bufferSize));
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path pt = new Path(path);
        FileSystem fs = pt.getFileSystem(configuration);
        FSDataOutputStream stream = null;
        if (append) {
          // append to a file only if file already exists else file not found
          // exception will be thrown by hdfs
          if (CarbonUtil.isFileExists(path)) {
            stream = fs.append(pt, bufferSize);
          } else {
            stream = fs.create(pt, true, bufferSize);
          }
        } else {
          stream = fs.create(pt, true, bufferSize);
        }
        return stream;
      default:
        path = getUpdatedFilePath(path, fileType);
        return new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(path), bufferSize));
    }
  }

  public static DataOutputStream getDataOutputStream(String path, FileType fileType, int bufferSize,
      long blockSize) throws IOException {
    path = path.replace("\\", "/");
    switch (fileType) {
      case LOCAL:
        path = getUpdatedFilePath(path, fileType);
        return new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(path), bufferSize));
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path pt = new Path(path);
        FileSystem fs = pt.getFileSystem(configuration);
        return fs.create(pt, true, bufferSize, fs.getDefaultReplication(pt), blockSize);
      default:
        path = getUpdatedFilePath(path, fileType);
        return new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(path), bufferSize));
    }
  }

  /**
   * This method checks the given path exists or not and also is it file or
   * not if the performFileCheck is true
   *
   * @param filePath         - Path
   * @param fileType         - FileType Local/HDFS
   * @param performFileCheck - Provide false for folders, true for files and
   */
  public static boolean isFileExist(String filePath, FileType fileType, boolean performFileCheck)
      throws IOException {
    filePath = filePath.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        if (performFileCheck) {
          return fs.exists(path) && fs.isFile(path);
        } else {
          return fs.exists(path);
        }

      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File defaultFile = new File(filePath);

        if (performFileCheck) {
          return defaultFile.exists() && defaultFile.isFile();
        } else {
          return defaultFile.exists();
        }
    }
  }

  /**
   * This method checks the given path exists or not and also is it file or
   * not if the performFileCheck is true
   *
   * @param filePath - Path
   * @param fileType - FileType Local/HDFS
   */
  public static boolean isFileExist(String filePath, FileType fileType) throws IOException {
    filePath = filePath.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        return fs.exists(path);

      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File defaultFile = new File(filePath);
        return defaultFile.exists();
    }
  }

  public static boolean createNewFile(String filePath, FileType fileType) throws IOException {
    filePath = filePath.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        return fs.createNewFile(path);

      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File file = new File(filePath);
        return file.createNewFile();
    }
  }

  public static boolean deleteFile(String filePath, FileType fileType) throws IOException {
    filePath = filePath.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        return fs.delete(path, true);

      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File file = new File(filePath);
        return deleteAllFilesOfDir(file);
    }
  }

  public static boolean deleteAllFilesOfDir(File path) {
    if (!path.exists()) {
      return true;
    }
    if (path.isFile()) {
      return path.delete();
    }
    File[] files = path.listFiles();
    if (null == files) {
      return true;
    }
    for (int i = 0; i < files.length; i++) {
      deleteAllFilesOfDir(files[i]);
    }
    return path.delete();
  }

  public static boolean deleteAllCarbonFilesOfDir(CarbonFile path) {
    if (!path.exists()) {
      return true;
    }
    if (!path.isDirectory()) {
      return path.delete();
    }
    CarbonFile[] files = path.listFiles();
    for (int i = 0; i < files.length; i++) {
      deleteAllCarbonFilesOfDir(files[i]);
    }
    return path.delete();
  }

  public static boolean mkdirs(String filePath, FileType fileType) throws IOException {
    filePath = filePath.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        return fs.mkdirs(path);
      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File file = new File(filePath);
        return file.mkdirs();
    }
  }

  /**
   * for getting the dataoutput stream using the hdfs filesystem append API.
   *
   * @param path
   * @param fileType
   * @return
   * @throws IOException
   */
  public static DataOutputStream getDataOutputStreamUsingAppend(String path, FileType fileType)
      throws IOException {
    path = path.replace("\\", "/");
    switch (fileType) {
      case LOCAL:
        path = getUpdatedFilePath(path, fileType);
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path, true)));
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path pt = new Path(path);
        FileSystem fs = pt.getFileSystem(configuration);
        return fs.append(pt);
      default:
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
    }
  }

  /**
   * this method will truncate the file to the new size.
   * @param path
   * @param fileType
   * @param newSize
   * @throws IOException
   */
  public static void truncateFile(String path, FileType fileType, long newSize) throws IOException {
    path = path.replace("\\", "/");
    FileChannel fileChannel = null;
    switch (fileType) {
      case LOCAL:
        path = getUpdatedFilePath(path, fileType);
        fileChannel = new FileOutputStream(path, true).getChannel();
        try {
          fileChannel.truncate(newSize);
        } finally {
          if (fileChannel != null) {
            fileChannel.close();
          }
        }
        return;
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        // if hadoop version >= 2.7, it can call method 'FileSystem.truncate' to truncate file,
        // this method was new in hadoop 2.7, otherwise use CarbonFile.truncate to do this.
        try {
          Path pt = new Path(path);
          FileSystem fs = pt.getFileSystem(configuration);
          Method truncateMethod = fs.getClass().getDeclaredMethod("truncate",
              new Class[]{Path.class, long.class});
          truncateMethod.invoke(fs, new Object[]{pt, newSize});
        } catch (NoSuchMethodException e) {
          LOGGER.error("the version of hadoop is below 2.7, there is no 'truncate'"
              + " method in FileSystem, It needs to use 'CarbonFile.truncate'.");
          CarbonFile carbonFile = FileFactory.getCarbonFile(path, fileType);
          carbonFile.truncate(path, newSize);
        } catch (Exception e) {
          LOGGER.error("Other exception occurred while truncating the file " + e.getMessage());
        }
        return;
      default:
        fileChannel = new FileOutputStream(path, true).getChannel();
        try {
          fileChannel.truncate(newSize);
        } finally {
          if (fileChannel != null) {
            fileChannel.close();
          }
        }
        return;
    }
  }

  /**
   * for creating a new Lock file and if it is successfully created
   * then in case of abrupt shutdown then the stream to that file will be closed.
   *
   * @param filePath
   * @param fileType
   * @return
   * @throws IOException
   */
  public static boolean createNewLockFile(String filePath, FileType fileType) throws IOException {
    filePath = filePath.replace("\\", "/");
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        if (fs.createNewFile(path)) {
          fs.deleteOnExit(path);
          return true;
        }
        return false;
      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File file = new File(filePath);
        return file.createNewFile();
    }
  }

  public enum FileType {
    LOCAL, HDFS, ALLUXIO, VIEWFS, S3
  }

  /**
   * below method will be used to update the file path
   * for local type
   * it removes the file:/ from the path
   *
   * @param filePath
   * @param fileType
   * @return updated file path without url for local
   */
  public static String getUpdatedFilePath(String filePath, FileType fileType) {
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        return filePath;
      case LOCAL:
      default:
        if (filePath != null && !filePath.isEmpty()) {
          // If the store path is relative then convert to absolute path.
          if (filePath.startsWith("./")) {
            try {
              return new File(filePath).getCanonicalPath();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          } else {
            Path pathWithoutSchemeAndAuthority =
                Path.getPathWithoutSchemeAndAuthority(new Path(filePath));
            return pathWithoutSchemeAndAuthority.toString();
          }
        } else {
          return filePath;
        }
    }
  }

  /**
   * below method will be used to update the file path
   * for local type
   * it removes the file:/ from the path
   *
   * @param filePath
   * @return updated file path without url for local
   */
  public static String getUpdatedFilePath(String filePath) {
    FileType fileType = getFileType(filePath);
    return getUpdatedFilePath(filePath, fileType);
  }

  /**
   * It computes size of directory
   *
   * @param filePath
   * @return size in bytes
   * @throws IOException
   */
  public static long getDirectorySize(String filePath) throws IOException {
    FileType fileType = getFileType(filePath);
    switch (fileType) {
      case HDFS:
      case ALLUXIO:
      case VIEWFS:
      case S3:
        Path path = new Path(filePath);
        FileSystem fs = path.getFileSystem(configuration);
        return fs.getContentSummary(path).getLength();
      case LOCAL:
      default:
        filePath = getUpdatedFilePath(filePath, fileType);
        File file = new File(filePath);
        return FileUtils.sizeOfDirectory(file);
    }
  }

  /**
   * This method will create the path object for a given file
   *
   * @param filePath
   * @return
   */
  public static Path getPath(String filePath) {
    return new Path(filePath);
  }

  /**
   * This method will return the filesystem instance
   *
   * @param path
   * @return
   * @throws IOException
   */
  public static FileSystem getFileSystem(Path path) throws IOException {
    return path.getFileSystem(configuration);
  }

}
