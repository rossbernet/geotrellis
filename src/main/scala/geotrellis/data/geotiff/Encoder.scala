package geotrellis.data.geotiff

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

import geotrellis._

// TODO: support writing more than one strip
// TODO: compression?
// TODO: color?

/**
 * Class for writing GeoTIFF data to a given DataOutputStream.
 *
 * This class implements the basic TIFF spec [1] and also supports the GeoTIFF
 * extension tags. It is not a general purpose TIFF encoder (in particular it
 * always writes the data into a single strip) but should work well for
 * encoding raster data.
 *
 * In addition to using one strip it uses a single sample per pixel and encodes
 * the data in one band. This means that files using more than 8-bit samples
 * will often not render correctly in image programs like the Gimp. It also
 * does not implement compression.
 *
 * Future work may add compression options, as well as options related to the
 * color palette, multiple bands, etc.
 *
 * Encoders are not thread-safe and can only be used to write a single raster
 * to single output stream.
 *
 * [1] http://partners.adobe.com/public/developer/en/tiff/TIFF6.pdf
 */
class Encoder(dos:DataOutputStream, raster:IntRaster, settings:Settings) {
  val data = raster.data
  val re = raster.rasterExtent
  val cols = re.cols
  val rows = re.rows
  val e = re.extent

  /**
   * Number of bits per sample. Should either be a multiple of 8, or evenly
   * divide 8 (i.e. 1, 2 or 4).
   */ 
  final def bitsPerSample:Int = settings.size.bits

  /**
   * Type of samples, using numeric constants from the TIFF spec.
   */
  final def sampleFormat:Int = settings.format.kind

  /**
   * Int nodata value to use when writing raster.
   */
  final def noDataValue:Int = settings.format match {
    case Signed => (1L << (bitsPerSample - 1)).toInt
    case Unsigned => ((1L << bitsPerSample) - 1).toInt
    case Floating => sys.error("floating point not supported")
  }

  /**
   * String nodata value to use in GeoTIFF metadata.
   */
  final def noDataString:String = settings.format match {
    case Signed => "-" + (1L << (bitsPerSample - 1)).toString
    case Unsigned => ((1L << bitsPerSample) - 1).toString
    case Floating => sys.error("floating point not supported")
  }

  /**
   * The number of TIFF tags to be written.
   *
   * If we update the writer to support additional TIFF tags this number needs
   * to be increased also. The writeTag calls in write are numbered to help
   * make this process easier.
   */
  final def numEntries = 18

  // we often need to "defer" writing data blocks, but keep track of how much
  // data would be written, to correctly compute future data block addresses.
  // we use 'todo' (backed by 'baos') to store this kind of data, and the
  // 'dataOffset' function to compute these addresses.
  val baos = new ByteArrayOutputStream()
  val todo = new DataOutputStream(baos)

  // The address we will start writing the image to.
  final def imageStartOffset:Int = 8

  // The addres we expect to see IFD information at.
  final def ifdOffset = imageStartOffset + img.size

  // The current address we will write (non-image) "data blocks" to.
  final def dataOffset = ifdOffset + 2 + (numEntries * 12) + 4 + baos.size

  // The byte array we will write all out 'strips' of image data to. We need
  // this to correctly compute addresses beyond the image (i.e. TIFF headers
  // and data blocks).
  val img = new ByteArrayOutputStream()

  // The number of bytes we've written to our 'dos'. Only used for debugging.
  var index:Int = 0

  // Used to immediately write values to our ultimate destination.
  def writeByte(value:Int) { dos.writeByte(value); index += 1 }
  def writeShort(value:Int) { dos.writeShort(value); index += 2 }
  def writeInt(value:Int) { dos.writeInt(value); index += 4 }
  def writeLong(value:Long) { dos.writeLong(value); index += 8 }
  def writeFloat(value:Float) { dos.writeFloat(value); index += 4 }
  def writeDouble(value:Double) { dos.writeDouble(value); index += 8 }

  // Used to write data blocks to our "deferred" byte array.
  def todoByte(value:Int) { todo.writeByte(value) }
  def todoShort(value:Int) { todo.writeShort(value) }
  def todoInt(value:Int) { todo.writeInt(value) }
  def todoLong(value:Long) { todo.writeLong(value) }
  def todoFloat(value:Float) { todo.writeFloat(value) }
  def todoDouble(value:Double) { todo.writeDouble(value) }

  // High-level function to defer writing geotiff tags.
  def todoGeoTag(tag:Int, loc:Int, count:Int, offset:Int) {
    todoShort(tag)
    todoShort(loc)
    todoShort(count)
    todoShort(offset)
  }

  // High-level function to write strings (char arrays). This will do immediate
  // writes for the header information, and possibly also do deferred writes to
  // the data blocks (if the null-terminated string data can't fit in 4 bytes).
  def writeString(tag:Int, s:String) {
    writeShort(tag)
    writeShort(2)
    writeInt(s.length + 1)
    if (s.length < 4) {
      var j = 0
      while (j < s.length) { writeByte(s(j).toInt); j += 1 }
      while (j < 4) { writeByte(0); j += 1 }
    } else {
      writeInt(dataOffset)
      var j = 0
      while (j < s.length) { todoByte(s(j).toInt); j += 1 }
      todoByte(0)
    }
  }

  // High-level function to immediately write a TIFF tag. if value is an offset
  // then the user is responsible for making sure it is valid.
  def writeTag(tag:Int, typ:Int, count:Int, value:Int) {
    writeShort(tag)
    writeShort(typ)
    writeInt(count)
    if (count > 1) {
      writeInt(value)
    } else if (typ == 1 || typ == 6) {
      writeByte(value)
      writeByte(0)
      writeShort(0)
    } else if (typ == 3 || typ == 8) {
      writeShort(value)
      writeShort(0)
    } else {
      writeInt(value)
    }
  }

  // Immediately write a byte array to the output.
  def writeByteArrayOutputStream(b:ByteArrayOutputStream) {
    b.writeTo(dos)
    index += b.size
  }

  // Render the raster data into strips, and return an array of file offsets
  // for each strip. Currently we write all the data into one strip so we just
  // return an array of one item.
  def renderImage():Array[Int] = {
    val dmg = new DataOutputStream(img)
    var row = 0

    val bits = bitsPerSample
    val nd = noDataValue

    while (row < rows) {
      val rowspan = row * cols
      var col = 0
      while (col < cols) {
        var z = data(rowspan + col)
        if (z == Int.MinValue) z = nd
        if (bits == 8) dmg.writeByte(z)
        else if (bits == 16) dmg.writeShort(z)
        else if (bits == 32) dmg.writeInt(z)
        else sys.error("unsupported bits/sample: %s" format bits)
        col += 1
      }
      row += 1
    }
    Array(imageStartOffset)
  }

  /**
   * Encodes the raster to GeoTIFF, and writes the data to the output stream.
   *
   * This method does not return a result; the result is written into the
   * output stream provided to Encoder's constructor. Many of the methods used
   * by write mutate the object, and Encoder is not thread-safe, so it's
   * important not to call this more than once.
   */
  def write() {
    // 0x0000: first 4 bytes of signature
    writeInt(0x4d4d002a)

    // render image (does not write output)
    val stripOffsets = renderImage()

    // 0x0004: offset to the first IFD
    writeInt(ifdOffset)

    // 0x0008: image data
    writeByteArrayOutputStream(img)

    // number of directory entries
    writeShort(numEntries)

    // 1. image width (cols)
    writeTag(0x0100, Const.uint32, 1, cols)

    // 2. image length (rows)
    writeTag(0x0101, Const.uint32, 1, rows)

    // 3. bits per sample
    writeTag(0x0102, Const.uint16, 1, bitsPerSample)

    // 4. compression is off (1)
    writeTag(0x0103, Const.uint16, 1, 1)

    // 5. photometric interpretation, black is zero (1)
    writeTag(0x0106, Const.uint16, 1, 1)

    // 6. strip offsets (actual image data)
    if (stripOffsets.length == 1) {
      // if we have one strip, write it's address in the tag
      writeTag(0x0111, Const.uint32, 1, stripOffsets(0))
    } else {
      // if we have multiple strips, write each addr to a data block
      writeTag(0x0111, Const.uint32, stripOffsets.length, dataOffset)
      stripOffsets.foreach(addr => todoInt(addr))
    }

    // 7. 1 sample per pixel
    writeTag(0x0115, Const.uint16, 1, 1)

    // 8. 'rows' rows per strip
    writeTag(0x0116, Const.uint32, 1, rows)

    // 9. strip byte counts (1 strip)
    writeTag(0x0117, Const.uint16, 1, img.size)

    // 10. y resolution, 1 pixel per unit
    writeTag(0x011a, Const.rational, 1, dataOffset)
    todoInt(1)
    todoInt(1)

    // 11. x resolution, 1 pixel per unit
    writeTag(0x011b, Const.rational, 1, dataOffset)
    todoInt(1)
    todoInt(1)

    // 12. single image plane (1)
    writeTag(0x011c, Const.uint16, 1, 1)

    // 13. resolution unit is undefined (1)
    writeTag(0x0128, Const.uint16, 1, 1)

    // 14. sample format (1=unsigned, 2=signed, 3=floating point)
    writeTag(0x0153, Const.uint16, 1, sampleFormat)

    // 15. model pixel scale tag (points to doubles sX, sY, sZ with sZ = 0)
    writeTag(0x830e, Const.float64, 3, dataOffset)
    todoDouble(re.cellwidth)  // sx
    todoDouble(re.cellheight) // sy
    todoDouble(0.0)           // sz = 0.0

    // 16. model tie point (doubles I,J,K (grid) and X,Y,Z (geog) with K = Z = 0.0)
    writeTag(0x8482, Const.float64, 6, dataOffset)
    todoDouble(0.0)    // i
    todoDouble(0.0)    // j
    todoDouble(0.0)    // k = 0.0
    todoDouble(e.xmin) // x
    todoDouble(e.ymax) // y
    todoDouble(0.0)    // z = 0.0

    // 17. geo key directory tag (4 geotags each with 4 short values)
    writeTag(0x87af, Const.uint16, 4 * 4, dataOffset)
    todoGeoTag(1, 1, 2, 3)       // geotif 1.2, 3 more tags
    todoGeoTag(1024, 0, 1, 1)    // projected data (undef=0, projected=1, geographic=2, geocenteric=3)
    todoGeoTag(1025, 0, 1, 1)    // area data (undef=0, area=1, point=2)
    todoGeoTag(3072, 0, 1, 3857) // gt projected cs type (3857)

    // 18. nodata as string
    writeString(0xa481, noDataString)

    // no more ifd entries
    writeInt(0)

    // write all our data blocks
    writeByteArrayOutputStream(baos)

    dos.flush()
  }
}

/**
 * The Encoder object provides several useful static methods for encoding 
 * raster data, which create Encoder instances on demand.
 */
object Encoder {

  /**
   * Encode raster as GeoTIFF and return an array of bytes.
   */
  def writeBytes(raster:IntRaster, settings:Settings) = {
    val baos = new ByteArrayOutputStream()
    val dos = new DataOutputStream(baos)
    val encoder = new Encoder(dos, raster, settings)
    encoder.write()
    baos.toByteArray
  }

  /**
   * Encode raster as GeoTIFF and write the data to the given path.
   */
  def writePath(path:String, raster:IntRaster, settings:Settings) {
    val fos = new FileOutputStream(new File(path))
    val dos = new DataOutputStream(fos)
    val encoder = new Encoder(dos, raster, settings)
    encoder.write()
    fos.close()
  }
}
