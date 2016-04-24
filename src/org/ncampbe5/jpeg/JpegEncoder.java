package org.ncampbe5.jpeg;

import java.awt.Frame;
import java.awt.Image;
import java.awt.MediaTracker;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

//Version 1.0a
//Copyright (C) 1998, James R. Weeks and BioElectroMech.
//Visit BioElectroMech at www.obrador.com. Email James@obrador.com.

//See license.txt for details about the allowed used of this software.
//This software is based in part on the work of the Independent JPEG Group.
//See IJGreadme.txt for details about the Independent JPEG Group's license.

//This encoder is inspired by the Java Jpeg encoder by Florian Raemy,
//studwww.eurecom.fr/~raemy.
//It borrows a great deal of code and structure from the Independent
//Jpeg Group's Jpeg 6a library, Copyright Thomas G. Lane.
//See license.txt for details.

/*
 * JpegEncoder - The JPEG main program which performs a jpeg compression of an
 * image.
 */
class JpegEncoder extends Frame {
	/**
	 * 
	 */
	private static final long serialVersionUID = 5437281984725638510L;

	Thread runner;

	BufferedOutputStream outStream;

	Image image;

	JpegInfo JpegObj;

	Huffman Huf;

	DCT dct;

	int imageHeight, imageWidth;

	int Quality;

	int code;

	public static int[] jpegNaturalOrder = { 0, 1, 8, 16, 9, 2, 3, 10, 17, 24,
			32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13,
			6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23,
			30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47,
			55, 62, 63, };

	public JpegEncoder(Image image, int quality, OutputStream out) {
		MediaTracker tracker = new MediaTracker(this);
		tracker.addImage(image, 0);
		try {
			tracker.waitForID(0);
		} catch (InterruptedException e) {
			// Got to do something?
		}
		/*
		 * Quality of the image. 0 to 100 and from bad image quality, high
		 * compression to good image quality low compression
		 */
		Quality = quality;

		/*
		 * Getting picture information It takes the Width, Height and RGB scans
		 * of the image.
		 */
		JpegObj = new JpegInfo(image);

		imageHeight = JpegObj.imageHeight;
		imageWidth = JpegObj.imageWidth;
		outStream = new BufferedOutputStream(out);
		dct = new DCT(Quality);
		Huf = new Huffman(imageWidth, imageHeight);
	}

	public void setQuality(int quality) {
		dct = new DCT(quality);
	}

	public int getQuality() {
		return Quality;
	}

	public void Compress() {
		WriteHeaders(outStream);
		long startTime = System.currentTimeMillis();
		WriteCompressedData(outStream);
		long endTime = System.currentTimeMillis();
		System.out.println("WriteCompressedData time = " + (endTime-startTime) + " ms.");
		WriteEOI(outStream);
		try {
			outStream.flush();
		} catch (IOException e) {
			System.out.println("IO Error: " + e.getMessage());
		}
	}

	public void WriteCompressedData(BufferedOutputStream outStream) {
		int i, j, r, c, a, b;
		int comp, xpos, ypos, xblockoffset, yblockoffset;
		float inputArray[][];
		float dctArray1[][] = new float[8][8];
		double dctArray2[][] = new double[8][8];
		int dctArray3[] = new int[8 * 8];

		/*
		 * This method controls the compression of the image. Starting at the
		 * upper left of the image, it compresses 8x8 blocks of data until the
		 * entire image has been compressed.
		 */
		int lastDCvalue[] = new int[JpegObj.NumberOfComponents];
		int MinBlockWidth, MinBlockHeight;
		// This initial setting of MinBlockWidth and MinBlockHeight is done to
		// ensure they start with values larger than will actually be the case.
		MinBlockWidth = ((imageWidth % 8 != 0) ? (int) (Math
				.floor(imageWidth / 8.0) + 1) * 8 : imageWidth);
		MinBlockHeight = ((imageHeight % 8 != 0) ? (int) (Math
				.floor(imageHeight / 8.0) + 1) * 8 : imageHeight);
		for (comp = 0; comp < JpegObj.NumberOfComponents; comp++) {
			MinBlockWidth = Math.min(MinBlockWidth, JpegObj.BlockWidth[comp]);
			MinBlockHeight = Math.min(MinBlockHeight, JpegObj.BlockHeight[comp]);
		}
		System.out.println("MinBlockHeight = " + MinBlockHeight);
		System.out.println("MinBlockHeight = " + MinBlockWidth);
		System.out.println("JpegObj.HsampFactor[0] = " + JpegObj.HsampFactor[0]);
		System.out.println("JpegObj.NumberOfComponents = " + JpegObj.NumberOfComponents);
		
		class PixelBlock {
			public int index;
			public int row, col, comp;
			public int dct3[] = new int[8*8];
			public boolean done = false;
			
			public PixelBlock(int index, int row, int col, int comp) {
				this.index = index;
				this.row = row;
				this.col = col;
				this.comp = comp;
			}
		}
		
		final Queue<PixelBlock> pixelBlocks = new ConcurrentLinkedQueue<PixelBlock>();
		final List<PixelBlock> transformedAndQuantizedPixelBlocks = new ArrayList<PixelBlock>();
		
		class TransformAndQuantizeThread extends Thread {
			private float dct1[][] = new float[8][8];
			private double dct2[][] = new double[9][9];
			private float inputArray[][];
			
			public TransformAndQuantizeThread() {
				super("TransformAndQuantizeThread");
			}
			
			public void run() {
				while (true) {
					PixelBlock pb = pixelBlocks.poll();
					if (pb != null) {
						int xpos = pb.col * 8;
						int ypos = pb.row * 8;
						int xblockoffset, yblockoffset;
						inputArray = (float[][]) JpegObj.Components[pb.comp];
						for (int i = 0; i < JpegObj.VsampFactor[pb.comp]; i++) {
							for (int j = 0; j < JpegObj.HsampFactor[pb.comp]; j++) {
								xblockoffset = j * 8;
								yblockoffset = i * 8;
								for (int a = 0; a < 8; a++) {
									for (int b = 0; b < 8; b++) {
										dct1[a][b] = inputArray[ypos + yblockoffset + a][xpos + xblockoffset + b];
									}
								}
								dct2 = dct.forwardDCT(dct1);
								pb.dct3 = dct.quantizeBlock(dct2, JpegObj.QtableNumber[pb.comp]);
							}
						}
						//System.out.println("Transformed and quantized pixel block " + pb.index);
						pb.done = true;
						transformedAndQuantizedPixelBlocks.set(pb.index, pb);
					} else {
						try {
							Thread.sleep(1);
						} catch (InterruptedException ie) {}
					}
				}
			}
		}
		
		//long startTime = System.nanoTime();
		for (i = 0; i < 4; i++) {
			TransformAndQuantizeThread t = new TransformAndQuantizeThread();
			t.start();
		}
		int index = 0;
		for (r = 0; r < MinBlockHeight; r++) {
			for (c = 0; c < MinBlockWidth; c++) {
				for (comp = 0; comp < JpegObj.NumberOfComponents; comp++) {
					PixelBlock pb = new PixelBlock(index, r, c, comp);
					pixelBlocks.offer(pb);
					transformedAndQuantizedPixelBlocks.add(pb);
					index++;
				}
			}
		}
		//long endTime = System.nanoTime();
		//System.out.println("Time to start DCT and Quantize threads = " + (endTime-startTime) + " ns.");
		
		index = 0;
		while (index < transformedAndQuantizedPixelBlocks.size()) { // TODO: get rid of busy wait
			PixelBlock pb = transformedAndQuantizedPixelBlocks.get(index);
			if (pb.done) {
				//long startTime = System.nanoTime();
				Huf.HuffmanBlockEncoder(outStream, pb.dct3, lastDCvalue[pb.comp], JpegObj.DCtableNumber[pb.comp], JpegObj.ACtableNumber[pb.comp]);
				lastDCvalue[pb.comp] = pb.dct3[0];
				//long endTime = System.nanoTime();
				//System.out.println("HuffmanBlockEncoder time = " + (endTime-startTime) + " ns.");
				index++;
			} else { 
				try {
					Thread.sleep(1);
				} catch (InterruptedException ie) {}
				//System.out.println("Not done. index = " + index); 
			}
		}
		/*
		
		xpos = 0;
		for (r = 0; r < MinBlockHeight; r++) {
			for (c = 0; c < MinBlockWidth; c++) {
				xpos = c * 8;
				ypos = r * 8;
				for (comp = 0; comp < JpegObj.NumberOfComponents; comp++) {
					inputArray = (float[][]) JpegObj.Components[comp];
					for (i = 0; i < JpegObj.VsampFactor[comp]; i++) {
						for (j = 0; j < JpegObj.HsampFactor[comp]; j++) {
							xblockoffset = j * 8;
							yblockoffset = i * 8;
							for (a = 0; a < 8; a++) {
								for (b = 0; b < 8; b++) {
									dctArray1[a][b] = inputArray[ypos + yblockoffset + a][xpos + xblockoffset + b];
								}
							}
							//long startTime = System.nanoTime();
							dctArray2 = dct.forwardDCT(dctArray1);
							dctArray3 = dct.quantizeBlock(dctArray2, JpegObj.QtableNumber[comp]);
							//long endTime = System.nanoTime();
							//System.out.println("DCT and Quantize time = " + (endTime-startTime) + " ns.");
							
							//startTime = System.nanoTime();
							Huf.HuffmanBlockEncoder(outStream, dctArray3, lastDCvalue[comp], JpegObj.DCtableNumber[comp], JpegObj.ACtableNumber[comp]);
							lastDCvalue[comp] = dctArray3[0];
							//endTime = System.nanoTime();
							//System.out.println("HuffmanBlockEncoder time = " + (endTime-startTime) + " ns.");
						}
					}
				}
			}
		}
		*/
		
		Huf.flushBuffer(outStream);
	}

	public void WriteEOI(BufferedOutputStream out) {
		byte[] EOI = { (byte) 0xFF, (byte) 0xD9 };
		WriteMarker(EOI, out);
	}

	public void WriteHeaders(BufferedOutputStream out) {
		int i, j, index, offset, length;
		int tempArray[];

		// the SOI marker
		byte[] SOI = { (byte) 0xFF, (byte) 0xD8 };
		WriteMarker(SOI, out);

		// The order of the following headers is quiet inconsequential.
		// the JFIF header
		byte JFIF[] = new byte[18];
		JFIF[0] = (byte) 0xff;
		JFIF[1] = (byte) 0xe0;
		JFIF[2] = (byte) 0x00;
		JFIF[3] = (byte) 0x10;
		JFIF[4] = (byte) 0x4a;
		JFIF[5] = (byte) 0x46;
		JFIF[6] = (byte) 0x49;
		JFIF[7] = (byte) 0x46;
		JFIF[8] = (byte) 0x00;
		JFIF[9] = (byte) 0x01;
		JFIF[10] = (byte) 0x00;
		JFIF[11] = (byte) 0x00;
		JFIF[12] = (byte) 0x00;
		JFIF[13] = (byte) 0x01;
		JFIF[14] = (byte) 0x00;
		JFIF[15] = (byte) 0x01;
		JFIF[16] = (byte) 0x00;
		JFIF[17] = (byte) 0x00;
		WriteArray(JFIF, out);

		// Comment Header
		String comment = "";
		comment = JpegObj.getComment();
		length = comment.length();
		byte COM[] = new byte[length + 4];
		COM[0] = (byte) 0xFF;
		COM[1] = (byte) 0xFE;
		COM[2] = (byte) ((length >> 8) & 0xFF);
		COM[3] = (byte) (length & 0xFF);
		java.lang.System.arraycopy(JpegObj.Comment.getBytes(), 0, COM, 4,
				JpegObj.Comment.length());
		WriteArray(COM, out);

		// The DQT header
		// 0 is the luminance index and 1 is the chrominance index
		byte DQT[] = new byte[134];
		DQT[0] = (byte) 0xFF;
		DQT[1] = (byte) 0xDB;
		DQT[2] = (byte) 0x00;
		DQT[3] = (byte) 0x84;
		offset = 4;
		for (i = 0; i < 2; i++) {
			DQT[offset++] = (byte) ((0 << 4) + i);
			tempArray = (int[]) dct.quantum[i];
			for (j = 0; j < 64; j++) {
				DQT[offset++] = (byte) tempArray[jpegNaturalOrder[j]];
			}
		}
		WriteArray(DQT, out);

		// Start of Frame Header
		byte SOF[] = new byte[19];
		SOF[0] = (byte) 0xFF;
		SOF[1] = (byte) 0xC0;
		SOF[2] = (byte) 0x00;
		SOF[3] = (byte) 17;
		SOF[4] = (byte) JpegObj.Precision;
		SOF[5] = (byte) ((JpegObj.imageHeight >> 8) & 0xFF);
		SOF[6] = (byte) ((JpegObj.imageHeight) & 0xFF);
		SOF[7] = (byte) ((JpegObj.imageWidth >> 8) & 0xFF);
		SOF[8] = (byte) ((JpegObj.imageWidth) & 0xFF);
		SOF[9] = (byte) JpegObj.NumberOfComponents;
		index = 10;
		for (i = 0; i < SOF[9]; i++) {
			SOF[index++] = (byte) JpegObj.CompID[i];
			SOF[index++] = (byte) ((JpegObj.HsampFactor[i] << 4) + JpegObj.VsampFactor[i]);
			SOF[index++] = (byte) JpegObj.QtableNumber[i];
		}
		WriteArray(SOF, out);

		// The DHT Header
		byte DHT1[], DHT2[], DHT3[], DHT4[];
		int bytes, temp, oldindex, intermediateindex;
		length = 2;
		index = 4;
		oldindex = 4;
		DHT1 = new byte[17];
		DHT4 = new byte[4];
		DHT4[0] = (byte) 0xFF;
		DHT4[1] = (byte) 0xC4;
		for (i = 0; i < 4; i++) {
			bytes = 0;
			DHT1[index++ - oldindex] = (byte) ((int[]) Huf.bits.elementAt(i))[0];
			for (j = 1; j < 17; j++) {
				temp = ((int[]) Huf.bits.elementAt(i))[j];
				DHT1[index++ - oldindex] = (byte) temp;
				bytes += temp;
			}
			intermediateindex = index;
			DHT2 = new byte[bytes];
			for (j = 0; j < bytes; j++) {
				DHT2[index++ - intermediateindex] = (byte) ((int[]) Huf.val
						.elementAt(i))[j];
			}
			DHT3 = new byte[index];
			java.lang.System.arraycopy(DHT4, 0, DHT3, 0, oldindex);
			java.lang.System.arraycopy(DHT1, 0, DHT3, oldindex, 17);
			java.lang.System.arraycopy(DHT2, 0, DHT3, oldindex + 17, bytes);
			DHT4 = DHT3;
			oldindex = index;
		}
		DHT4[2] = (byte) (((index - 2) >> 8) & 0xFF);
		DHT4[3] = (byte) ((index - 2) & 0xFF);
		WriteArray(DHT4, out);

		// Start of Scan Header
		byte SOS[] = new byte[14];
		SOS[0] = (byte) 0xFF;
		SOS[1] = (byte) 0xDA;
		SOS[2] = (byte) 0x00;
		SOS[3] = (byte) 12;
		SOS[4] = (byte) JpegObj.NumberOfComponents;
		index = 5;
		for (i = 0; i < SOS[4]; i++) {
			SOS[index++] = (byte) JpegObj.CompID[i];
			SOS[index++] = (byte) ((JpegObj.DCtableNumber[i] << 4) + JpegObj.ACtableNumber[i]);
		}
		SOS[index++] = (byte) JpegObj.Ss;
		SOS[index++] = (byte) JpegObj.Se;
		SOS[index++] = (byte) ((JpegObj.Ah << 4) + JpegObj.Al);
		WriteArray(SOS, out);

	}

	void WriteMarker(byte[] data, BufferedOutputStream out) {
		try {
			out.write(data, 0, 2);
		} catch (IOException e) {
			System.out.println("IO Error: " + e.getMessage());
		}
	}

	void WriteArray(byte[] data, BufferedOutputStream out) {
		int length;
		try {
			length = ((data[2] & 0xFF) << 8) + (data[3] & 0xFF) + 2;
			out.write(data, 0, length);
		} catch (IOException e) {
			System.out.println("IO Error: " + e.getMessage());
		}
	}
}