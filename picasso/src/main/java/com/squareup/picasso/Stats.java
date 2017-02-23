/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.picasso;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

//状态(发现网络请求都有状态一说)
class Stats {
    //缓存击中
    private static final int CACHE_HIT = 0;
    //缓存未击中
    private static final int CACHE_MISS = 1;
    private static final int BITMAP_DECODE_FINISHED = 2;
    private static final int BITMAP_TRANSFORMED_FINISHED = 3;
    //下载完成
    private static final int DOWNLOAD_FINISHED = 4;

    private static final String STATS_THREAD_NAME = Utils.THREAD_PREFIX + "Stats";

    //Handler+Thread实现, 单线程任务队列  主线程向子线程发送消息
    final HandlerThread statsThread;
    final Cache cache;
    final Handler handler;

    long cacheHits;
    long cacheMisses;
    long totalDownloadSize;
    long totalOriginalBitmapSize;
    long totalTransformedBitmapSize;
    long averageDownloadSize;
    long averageOriginalBitmapSize;
    long averageTransformedBitmapSize;
    int downloadCount;
    int originalBitmapCount;
    int transformedBitmapCount;

    Stats(Cache cache) {
        this.cache = cache;
        //创建HandlerThread
        this.statsThread = new HandlerThread(STATS_THREAD_NAME, THREAD_PRIORITY_BACKGROUND);
        //开启
        this.statsThread.start();
        Utils.flushStackLocalLeaks(statsThread.getLooper());
        //创建Handler
        this.handler = new StatsHandler(statsThread.getLooper(), this);
    }

    void dispatchBitmapDecoded(Bitmap bitmap) {
        processBitmap(bitmap, BITMAP_DECODE_FINISHED);
    }

    void dispatchBitmapTransformed(Bitmap bitmap) {
        processBitmap(bitmap, BITMAP_TRANSFORMED_FINISHED);
    }

    void dispatchDownloadFinished(long size) {
        handler.sendMessage(handler.obtainMessage(DOWNLOAD_FINISHED, size));
    }

    void dispatchCacheHit() {
        handler.sendEmptyMessage(CACHE_HIT);
    }

    void dispatchCacheMiss() {
        handler.sendEmptyMessage(CACHE_MISS);
    }

    void shutdown() {
        statsThread.quit();
    }

    void performCacheHit() {
        cacheHits++;
    }

    void performCacheMiss() {
        cacheMisses++;
    }

    void performDownloadFinished(Long size) {
        downloadCount++;
        totalDownloadSize += size;
        averageDownloadSize = getAverage(downloadCount, totalDownloadSize);
    }

    void performBitmapDecoded(long size) {
        originalBitmapCount++;
        totalOriginalBitmapSize += size;
        averageOriginalBitmapSize = getAverage(originalBitmapCount, totalOriginalBitmapSize);
    }

    void performBitmapTransformed(long size) {
        transformedBitmapCount++;
        totalTransformedBitmapSize += size;
        averageTransformedBitmapSize = getAverage(originalBitmapCount, totalTransformedBitmapSize);
    }

    StatsSnapshot createSnapshot() {
        return new StatsSnapshot(cache.maxSize(), cache.size(), cacheHits, cacheMisses,
                totalDownloadSize, totalOriginalBitmapSize, totalTransformedBitmapSize, averageDownloadSize,
                averageOriginalBitmapSize, averageTransformedBitmapSize, downloadCount, originalBitmapCount,
                transformedBitmapCount, System.currentTimeMillis());
    }

    private void processBitmap(Bitmap bitmap, int what) {
        // Never send bitmaps to the handler as they could be recycled before we process them.
        int bitmapSize = Utils.getBitmapBytes(bitmap);
        handler.sendMessage(handler.obtainMessage(what, bitmapSize, 0));
    }

    private static long getAverage(int count, long totalSize) {
        return totalSize / count;
    }


    //保证了数据的同步
    private static class StatsHandler extends Handler {

        private final Stats stats;

        public StatsHandler(Looper looper, Stats stats) {
            super(looper);
            this.stats = stats;
        }

        @Override
        public void handleMessage(final Message msg) {
            switch (msg.what) {
                case CACHE_HIT:
                    stats.performCacheHit();
                    break;
                case CACHE_MISS:
                    stats.performCacheMiss();
                    break;
                case BITMAP_DECODE_FINISHED:
                    stats.performBitmapDecoded(msg.arg1);
                    break;
                case BITMAP_TRANSFORMED_FINISHED:
                    stats.performBitmapTransformed(msg.arg1);
                    break;
                case DOWNLOAD_FINISHED:
                    stats.performDownloadFinished((Long) msg.obj);
                    break;
                default:
                    Picasso.HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            throw new AssertionError("Unhandled stats message." + msg.what);
                        }
                    });
            }
        }
    }
}