package com.pumsok.story

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Concatenates multiple M4A (AAC) audio recordings into a single seamless M4A file
 * using Android's MediaExtractor and MediaMuxer without re-encoding.
 */
object AudioConcatenator {

    fun concatM4a(inputFiles: List<File>, outputFile: File, pauseUs: Long = 350_000L): Boolean {
        val validFiles = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) return false
        if (validFiles.size == 1) {
            return runCatching {
                validFiles[0].copyTo(outputFile, overwrite = true)
                true
            }.getOrDefault(false)
        }

        var muxer: MediaMuxer? = null
        val tempOutput = File(outputFile.parentFile, "${outputFile.name}.tmp")
        if (tempOutput.exists()) tempOutput.delete()

        return runCatching {
            muxer = MediaMuxer(tempOutput.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Extract audio format from the first file
            val firstExtractor = MediaExtractor().apply { setDataSource(validFiles[0].absolutePath) }
            val trackCount = firstExtractor.trackCount
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            firstExtractor.release()

            val formatToUse = audioFormat ?: error("첫 번째 파일에서 오디오 포맷을 찾을 수 없습니다.")
            val muxerTrackIndex = muxer!!.addTrack(formatToUse)
            muxer!!.start()

            var globalOffsetUs = 0L
            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (file in validFiles) {
                val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
                var fileAudioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        fileAudioTrack = i
                        break
                    }
                }

                if (fileAudioTrack == -1) {
                    extractor.release()
                    continue
                }

                extractor.selectTrack(fileAudioTrack)
                var lastSampleTimeUs = 0L

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTime = extractor.sampleTime
                    if (sampleTime > lastSampleTimeUs) {
                        lastSampleTimeUs = sampleTime
                    }
                    val sampleFlags = extractor.sampleFlags

                    bufferInfo.set(0, sampleSize, globalOffsetUs + sampleTime, sampleFlags)
                    muxer!!.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }

                extractor.release()
                globalOffsetUs += lastSampleTimeUs + pauseUs
            }

            muxer!!.stop()
            muxer!!.release()
            muxer = null

            if (outputFile.exists()) outputFile.delete()
            tempOutput.renameTo(outputFile)
        }.getOrElse {
            muxer?.runCatching { release() }
            tempOutput.delete()
            false
        }
    }
}
