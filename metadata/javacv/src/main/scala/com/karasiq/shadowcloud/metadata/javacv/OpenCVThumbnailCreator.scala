package com.karasiq.shadowcloud.metadata.javacv

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.config.Config
import org.bytedeco.javacpp.opencv_core._
import org.bytedeco.javacpp.opencv_imgcodecs._
import org.bytedeco.javacpp.opencv_imgproc._

import com.karasiq.shadowcloud.metadata.{Metadata, MetadataParser}
import com.karasiq.shadowcloud.metadata.config.MetadataParserConfig
import com.karasiq.shadowcloud.streams.utils.ByteStreams

private[javacv] object OpenCVThumbnailCreator {
  def apply(config: Config): OpenCVThumbnailCreator = {
    new OpenCVThumbnailCreator(config)
  }

  def getScaledDimension(width: Int, height: Int, widthConstraint: Int, heightConstraint: Int): (Int, Int) = {
    var newWidth: Int = width
    var newHeight: Int = height
    if (width > widthConstraint) {
      newWidth = widthConstraint
      newHeight = (newWidth * height) / width
    }
    if (newHeight > heightConstraint) {
      newHeight = heightConstraint
      newWidth = (newHeight * width) / height
    }
    (newWidth, newHeight)
  }

  def resizeIplImage(image: IplImage, widthConstraint: Int, heightConstraint: Int): IplImage = {
    val (newWidth, newHeight) = getScaledDimension(image.width(), image.height(), widthConstraint, heightConstraint)
    val thumb = cvCreateImage(cvSize(newWidth, newHeight), image.depth(), image.nChannels())
    cvResize(image, thumb)
    thumb
  }
}

private[javacv] class OpenCVThumbnailCreator(config: Config) extends MetadataParser {
  protected object settings {
    val parserConfig = MetadataParserConfig(config)
    val sizeLimit = config.getBytes("size-limit")
    val thumbnailSize = config.getInt("thumbnail-size")
    val thumbnailQuality = config.getInt("thumbnail-quality")
  }

  def canParse(name: String, mime: String) = {
    settings.parserConfig.canParse(name, mime)
  }

  def parseMetadata(name: String, mime: String) = {
    Flow[ByteString]
      .via(ByteStreams.limit(settings.sizeLimit))
      .via(ByteStreams.concat)
      .map { bytes ⇒
        require(bytes.nonEmpty, "Image is empty")
        val image = new IplImage(imdecode(new Mat(bytes:_*), CV_LOAD_IMAGE_UNCHANGED)) // cvDecodeImage(new CvMat(new Mat(bytes:_*)), CV_LOAD_IMAGE_UNCHANGED)
        try {
          val thumb = OpenCVThumbnailCreator.resizeIplImage(image, settings.thumbnailSize, settings.thumbnailSize)
          try {
            val jpegBytes = ByteString.fromArrayUnsafe(JavaCV.asJpeg(thumb, settings.thumbnailQuality))
            Metadata(Some(Metadata.Tag("javacv", "opencv", Metadata.Tag.Disposition.PREVIEW)),
              Metadata.Value.Thumbnail(Metadata.Thumbnail("jpeg", jpegBytes)))
          } finally thumb.release()
        } finally image.release()
      }
      .named("opencvThumbnail")
  }
}
