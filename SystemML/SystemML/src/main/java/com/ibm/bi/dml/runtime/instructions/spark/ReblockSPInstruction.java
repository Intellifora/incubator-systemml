/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.instructions.spark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;

import scala.Tuple2;

import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.caching.MatrixObject;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.instructions.spark.data.RDDProperties;
import com.ibm.bi.dml.runtime.instructions.spark.functions.ConvertALToBinaryBlockFunction;
import com.ibm.bi.dml.runtime.instructions.spark.functions.ConvertTextLineToBinaryCellFunction;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.MatrixFormatMetaData;
import com.ibm.bi.dml.runtime.matrix.data.InputInfo;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixCell;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.util.UtilFunctions;

public class ReblockSPInstruction extends UnarySPInstruction {
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private int brlen; private int bclen;
	private boolean outputEmptyBlocks;
	
	
	public ReblockSPInstruction(Operator op, CPOperand in, CPOperand out, int br, int bc, boolean emptyBlocks,
			String opcode, String instr) {
		super(op, in, out, opcode, instr);
		brlen=br;
		bclen=bc;
		outputEmptyBlocks = emptyBlocks;
	}
	
	public static Instruction parseInstruction(String str)  throws DMLRuntimeException 
	{
		String opcode = InstructionUtils.getOpCode(str);
		if(opcode.compareTo("rblk") != 0) {
			throw new DMLRuntimeException("Incorrect opcode for ReblockSPInstruction:" + opcode);
		}
		
		// Example parts of ReblockSPInstruction: [rblk, pREADG·MATRIX·DOUBLE, _mVar1·MATRIX·DOUBLE, 1000, 1000, true]
		String parts[] = InstructionUtils.getInstructionPartsWithValueType(str);
		
		CPOperand in = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand out = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		in.split(parts[1]);
		out.split(parts[2]);
		int brlen=Integer.parseInt(parts[3]);
		int bclen=Integer.parseInt(parts[4]);
		boolean outputEmptyBlocks = Boolean.parseBoolean(parts[5]);
		
		Operator op = null; // no operator for ReblockSPInstruction
		return new ReblockSPInstruction(op, in, out, brlen, bclen, outputEmptyBlocks, opcode, str);
	}
	
	public void processTextCellReblock(SparkExecutionContext sec, JavaPairRDD<LongWritable, Text> lines) throws DMLRuntimeException {
		MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
		MatrixCharacteristics mc = sec.getMatrixCharacteristics(input1.getName());
		
		long numRows = -1;
		long numColumns = -1;
		if(!mcOut.dimsKnown() && !mc.dimsKnown()) {
			throw new DMLRuntimeException("Unknown dimensions in reblock instruction for text format");
		}
		else if(mc.dimsKnown()) {
			numRows = mc.getRows();
			numColumns = mc.getCols();
		}
		else {
			numRows = mcOut.getRows();
			numColumns = mcOut.getCols();
		}
		
		if(numRows <= 0 || numColumns <= 0) {
			throw new DMLRuntimeException("Error: Incorrect input dimensions:" + numRows + "," +  numColumns); 
		}
		
		JavaPairRDD<MatrixIndexes, MatrixCell> binaryCells = 
				lines.mapToPair(new ConvertTextLineToBinaryCellFunction(brlen, bclen))
				.filter(new DropEmptyBinaryCells());
				
		// TODO: Investigate whether binaryCells.persist() will help here or not
		
		// ----------------------------------------------------------------------------
		// Now merge binary cells into binary blocks
		// Here you provide three "extremely light-weight" functions (that ignores sparsity):
		// 1. cell -> ArrayList (AL)
		// 2. (AL, cell) -> AL
		// 3. (AL, AL) -> AL
		// Then you convert the final AL -> binary blocks (here you take into account the sparsity).
		JavaPairRDD<MatrixIndexes, MatrixBlock> binaryBlocksWithoutEmptyBlocks =
				binaryCells.combineByKey(
						new ConvertCellToALFunction(), 
						new AddCellToALFunction(), 
						new MergeALFunction())
						.mapToPair(new ConvertALToBinaryBlockFunction(brlen, bclen, numRows, numColumns));		
		// ----------------------------------------------------------------------------
		
		JavaPairRDD<MatrixIndexes, MatrixBlock> binaryBlocksWithEmptyBlocks = null;
		if(outputEmptyBlocks) {
			// ----------------------------------------------------------------------------
			// Now take care of empty blocks
			// This is done as non-rdd operation due to complexity involved in "not in" operations
			// Since this deals only with keys and not blocks, it might not be that bad.
			List<MatrixIndexes> indexes = binaryBlocksWithoutEmptyBlocks.keys().collect();
			ArrayList<Tuple2<MatrixIndexes, MatrixBlock> > emptyBlocksList = getEmptyBlocks(indexes, numRows, numColumns);
			if(emptyBlocksList != null && emptyBlocksList.size() > 0) {
				// Empty blocks needs to be inserted
				binaryBlocksWithEmptyBlocks = JavaPairRDD.fromJavaRDD(sec.getSparkContext().parallelize(emptyBlocksList))
						.union(binaryBlocksWithoutEmptyBlocks);
			}
			else {
				binaryBlocksWithEmptyBlocks = binaryBlocksWithoutEmptyBlocks;
			}
			// ----------------------------------------------------------------------------
		}
		else {
			binaryBlocksWithEmptyBlocks = binaryBlocksWithoutEmptyBlocks;
		}
		
		
		//put output RDD handle into symbol table
		sec.setRDDHandleForVariable(output.getName(), binaryBlocksWithEmptyBlocks);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void processInstruction(ExecutionContext ec)
			throws DMLRuntimeException, DMLUnsupportedOperationException {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		String opcode = getOpcode();
		
		if ( opcode.equalsIgnoreCase("rblk")) {
			MatrixObject mo = sec.getMatrixObject(input1.getName());
			MatrixCharacteristics mc = sec.getMatrixCharacteristics(input1.getName());
			MatrixCharacteristics mcOut = sec.getMatrixCharacteristics(output.getName());
			if(!mcOut.dimsKnown() && mc.dimsKnown()) {
				int brlen_out = mcOut.getRowsPerBlock();
				int bclen_out = mcOut.getColsPerBlock();
				// The number of rows and columns remains the same for input and output
				// However, the block size may vary: For example: global dataflow optimization
				mcOut.set(mc.getRows(), mc.getCols(), brlen_out, bclen_out);
				// System.out.println("In Reblock, 1. Setting " + output.getName() + " to " + mc.toString());
			}
			if(mcOut.dimsKnown() && !mc.dimsKnown()) {
				int brlen_in = mc.getRowsPerBlock();
				int bclen_in = mc.getColsPerBlock();
				// The number of rows and columns remains the same for input and output
				// However, the block size may vary: For example: global dataflow optimization
				mc.set(mcOut.getRows(), mcOut.getCols(), brlen_in, bclen_in);
				// System.out.println("In Reblock, 2. Setting " + input1.getName() + " to " + mcOut.toString());
			}
			
			MatrixFormatMetaData iimd = (MatrixFormatMetaData) mo.getMetaData();
			
			if(iimd == null) {
				throw new DMLRuntimeException("Error: Metadata not found");
			}
			
			if(iimd.getInputInfo() == InputInfo.TextCellInputInfo || iimd.getInputInfo() == InputInfo.MatrixMarketInputInfo ) {
				JavaPairRDD<LongWritable, Text> lines = (JavaPairRDD<LongWritable, Text>) sec.getRDDHandleForVariable(input1.getName(), iimd.getInputInfo());
				processTextCellReblock(sec, lines);
			}
			else if(iimd.getInputInfo() == InputInfo.CSVInputInfo) {
				// HACK ALERT: Until we introduces the rewrite to insert csvrblock for non-persistent read
				// throw new DMLRuntimeException("CSVInputInfo is not supported for ReblockSPInstruction");
				RDDProperties properties = mo.getRddProperties();
				CSVReblockSPInstruction csvInstruction = null;
				boolean hasHeader = false;
				String delim = ",";
				boolean fill = false;
				double missingValue = 0;
				if(properties != null) {
					hasHeader = properties.isHasHeader();
					delim = properties.getDelim();
					fill = properties.isFill();
					missingValue = properties.getMissingValue();
				}
				csvInstruction = new CSVReblockSPInstruction(null, input1, output, mcOut.getRowsPerBlock(), mcOut.getColsPerBlock(), hasHeader, delim, fill, missingValue, "csvrblk", instString);
				csvInstruction.processInstruction(sec);
				return;
			}
			else if(iimd.getInputInfo()==InputInfo.BinaryCellInputInfo) {
				// TODO:
				throw new DMLRuntimeException("BinaryCellInputInfo is not implemented for ReblockSPInstruction");
			}
			else if(iimd.getInputInfo()==InputInfo.BinaryBlockInputInfo) {
				/// HACK ALERT: Workaround for MLContext 
				if(mc.getRowsPerBlock() == mcOut.getRowsPerBlock() && mc.getColsPerBlock() == mcOut.getColsPerBlock()) {
					if(mo.getRDDHandle() != null) {
						sec.setRDDHandleForVariable(output.getName(), (JavaPairRDD<MatrixIndexes, MatrixBlock>) mo.getRDDHandle().getRDD() );
						return;
					}
					else {
						throw new DMLRuntimeException("Input RDD is not accessible through buffer pool for ReblockSPInstruction:" + iimd.getInputInfo());
					}
				}
				else {
					// TODO: For global data flow optimization
					throw new DMLRuntimeException("Reblocking to different sizes is not implemented for ReblockSPInstruction:" + iimd.getInputInfo());
				}
			}
			else {
				throw new DMLRuntimeException("The given InputInfo is not implemented for ReblockSPInstruction:" + iimd.getInputInfo());
			}		
		} 
		else {
			throw new DMLRuntimeException("In ReblockSPInstruction,  Unknown opcode in Instruction: " + toString());
		}
	}
	
	private ArrayList<Tuple2<MatrixIndexes, MatrixBlock>> getEmptyBlocks(List<MatrixIndexes> nonEmptyIndexes, long rlen, long clen) throws DMLRuntimeException {
		long numBlocksPerRow = (long) Math.ceil((double)rlen / brlen);
		long numBlocksPerCol = (long) Math.ceil((double)clen / bclen);
		long expectedNumBlocks = numBlocksPerRow*numBlocksPerCol;
		
		if(expectedNumBlocks == nonEmptyIndexes.size()) {
			return null; // no empty blocks required: sanity check
		}
		else if(expectedNumBlocks < nonEmptyIndexes.size()) {
			throw new DMLRuntimeException("Error: Incorrect number of indexes in ReblockSPInstruction:" + nonEmptyIndexes.size());
		}
		
		// ----------------------------------------------------------------------------
		// Add empty blocks: Performs a "not-in" operation
		Collections.sort(nonEmptyIndexes); // sort in ascending order first wrt rows and then wrt columns
		ArrayList<Tuple2<MatrixIndexes, MatrixBlock>> retVal = new ArrayList<Tuple2<MatrixIndexes,MatrixBlock>>();
		int index = 0;
		for(long row = 1; row <=  Math.ceil((double)rlen / brlen); row++) {
			for(long col = 1; col <=  Math.ceil((double)clen / bclen); col++) {
				boolean matrixBlockExists = false;
				if(nonEmptyIndexes.size() > index) {
					matrixBlockExists = (nonEmptyIndexes.get(index).getRowIndex() == row) && (nonEmptyIndexes.get(index).getColumnIndex() == col);
				}
				if(matrixBlockExists) {
					index++; // No need to add empty block
				}
				else {
					// ------------------------------------------------------------------
					//	Compute local block size: 
					// Example: For matrix: 1500 X 1100 with block length 1000 X 1000
					// We will have four local block sizes (1000X1000, 1000X100, 500X1000 and 500X1000)
					long blockRowIndex = row;
					long blockColIndex = col;
					int emptyBlk_lrlen = UtilFunctions.computeBlockSize(rlen, blockRowIndex, brlen);
					int emptyBlk_lclen = UtilFunctions.computeBlockSize(clen, blockColIndex, bclen);
					// ------------------------------------------------------------------
					
					MatrixBlock emptyBlk = new MatrixBlock(emptyBlk_lrlen, emptyBlk_lclen, true);
					retVal.add(new Tuple2<MatrixIndexes, MatrixBlock>(new MatrixIndexes(blockRowIndex, blockColIndex), emptyBlk));
				}
			}
		}
		// ----------------------------------------------------------------------------
		
		if(index != nonEmptyIndexes.size()) {
			throw new DMLRuntimeException("Unexpected error while adding empty blocks in ReblockSPInstruction");
		}
		
		return retVal;
	}
	
	// ====================================================================================================
	// Three functions passed to combineByKey
	
	public static class ConvertCellToALFunction implements Function<MatrixCell, ArrayList<MatrixCell>> {
		private static final long serialVersionUID = -2458721762929481811L;
		@Override
		public ArrayList<MatrixCell> call(MatrixCell cell) throws Exception {
			ArrayList<MatrixCell> retVal = new ArrayList<MatrixCell>();
			if(cell.getValue() != 0)
				retVal.add(cell);
			return retVal;
		}	
	}
	
	public static class AddCellToALFunction implements Function2<ArrayList<MatrixCell>, MatrixCell, ArrayList<MatrixCell>> {
		private static final long serialVersionUID = -4680403897867388102L;
		@Override
		public ArrayList<MatrixCell> call(ArrayList<MatrixCell> al, MatrixCell cell) throws Exception {
			al.add(cell);
			return al;
		}	
	}
	
	public static class MergeALFunction implements Function2<ArrayList<MatrixCell>, ArrayList<MatrixCell>, ArrayList<MatrixCell>> {
		private static final long serialVersionUID = -8117257799807223694L;
		@Override
		public ArrayList<MatrixCell> call(ArrayList<MatrixCell> al1, ArrayList<MatrixCell> al2) throws Exception {
			al1.addAll(al2);
			return al1;
		}	
	}
	// ====================================================================================================
	
	// This function gets called to check whether to drop binary cell corresponding to header of Matrix market format
	public static class DropEmptyBinaryCells implements Function<Tuple2<MatrixIndexes,MatrixCell>, Boolean> {
		private static final long serialVersionUID = -3672377410407066396L;
		
		@Override
		public Boolean call(Tuple2<MatrixIndexes, MatrixCell> arg0) throws Exception {
			if(arg0._1.getRowIndex() == -1) {
				return false; // Header cell for MatrixMarket format
			}
			else if(arg0._2.getValue() == 0) {
				return false; // empty cell: can be dropped as MatrixBlock can handle sparsity
			}
			return true;
		}
		
	}
}