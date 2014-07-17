/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2014
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */


package com.ibm.bi.dml.runtime.matrix.io;

import java.util.Arrays;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math.random.Well1024a;

import com.ibm.bi.dml.hops.DataGenOp;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.util.NormalPRNGenerator;
import com.ibm.bi.dml.runtime.util.PRNGenerator;
import com.ibm.bi.dml.runtime.util.UniformPRNGenerator;

/**
 *  
 */
public class LibMatrixDatagen 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2014\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	protected static final Log LOG = LogFactory.getLog(LibMatrixDatagen.class.getName());
	
	public static final String RAND_PDF_UNIFORM = "uniform";
	public static final String RAND_PDF_NORMAL = "normal";
	
	
	/**
	 * A matrix of random numbers is generated by using multiple seeds, one for each 
	 * block. Such block-level seeds are produced via Well equidistributed long-period linear 
	 * generator (Well1024a). For a given seed, this function sets up the block-level seeds.
	 * 
	 * This function is invoked from both CP (RandCPInstruction.processInstruction()) 
	 * as well as MR (RandMR.java while setting up the Rand job).
	 * 
	 * @param seed
	 * @return
	 */
	public static Well1024a setupSeedsForRand(long seed) 
	{
		long lSeed = (seed == DataGenOp.UNSPECIFIED_SEED ? DataGenOp.generateRandomSeed() : seed);
		LOG.trace("Setting up RandSeeds with initial seed = "+lSeed+".");

		Random random=new Random(lSeed);
		Well1024a bigrand=new Well1024a();
		//random.setSeed(lSeed);
		int[] seeds=new int[32];
		for(int s=0; s<seeds.length; s++)
			seeds[s]=random.nextInt();
		bigrand.setSeed(seeds);
		
		return bigrand;
	}
	
	
	public static long[] computeNNZperBlock(long nrow, long ncol, int brlen, int bclen, double sparsity) {
		int numBlocks = (int) (Math.ceil((double)nrow/brlen) * Math.ceil((double)ncol/bclen));
		//System.out.println("nrow=" + nrow + ", brlen=" + brlen + ", ncol="+ncol+", bclen=" + bclen + "::: " + Math.ceil(nrow/brlen));
		
		// CURRENT: 
		// 		Total #of NNZ is set to the expected value (nrow*ncol*sparsity).
		// TODO: 
		//		Instead of using the expected value, one should actually 
		// 		treat NNZ as a random variable and accordingly generate a random value.
		long nnz = (long) Math.ceil (nrow * (ncol*sparsity));
		
		//System.out.println("Number of blocks = " + numBlocks + "; NNZ = " + nnz);
		
		// Compute block-level NNZ
		long ret[]  = new long[numBlocks];
		Arrays.fill(ret, 0);
		
		if ( nnz < numBlocks ) {
			// Ultra-sparse matrix
			
			// generate the number of blocks with at least one non-zero
			// = a random number between [1,nnz]
			Random runif = new Random(System.nanoTime());
			int numNZBlocks = (int) (1 + Math.abs(runif.nextLong())%nnz);
			
			// distribute non-zeros across numNZBlocks

			// compute proportions for each nzblock 
			// - divide (0,1] interval into numNZBlocks portions of random size
			double[] blockNNZproportions = new double[numNZBlocks];
			
			runif.setSeed(System.nanoTime());
			for(int i=0; i < numNZBlocks-1; i++) {
				blockNNZproportions[i] = runif.nextDouble();
			}
			blockNNZproportions[numNZBlocks-1] = 1;
			// sort the values in ascending order
			Arrays.sort(blockNNZproportions);
			
			// compute actual number of non zeros per block according to proportions
			long actualnnz = 0;
			int bid;
			runif.setSeed(System.nanoTime());
			for(int i=0; i < numNZBlocks; i++) {
				bid = -1;
				do {
					bid = (int) (Math.abs(runif.nextLong())%numBlocks);
				} while( ret[bid] != 0);
				
				double prop = (i==0 ? blockNNZproportions[i]: (blockNNZproportions[i] - blockNNZproportions[i-1]));
				ret[bid] = (long)Math.floor(prop * nnz);
				actualnnz += ret[bid];
			}
			
			// Code to make sure exact number of non-zeros are generated
			while (actualnnz < nnz) {
				bid = (int) (Math.abs(runif.nextLong())%numBlocks);
				ret[bid]++;
				actualnnz++;
			}
		}
		else {
			int bid = 0;
			
			//long actualnnz = 0;
			for(long r = 0; r < nrow; r += brlen) {
				long curBlockRowSize = Math.min(brlen, (nrow - r));
				for(long c = 0; c < ncol; c += bclen)
				{
					long curBlockColSize = Math.min(bclen, (ncol - c));
					ret[bid] = (long) (curBlockRowSize * curBlockColSize * sparsity);
					//actualnnz += ret[bid];
					bid++;
				}
			}
		}
		return ret;
	}
	
	/**
	 * Function to generate a matrix of random numbers. This is invoked from maptasks of 
	 * DataGen MR job.  The parameter <code>seed</code> denotes the block-level seed.
	 * 
	 * @param pdf
	 * @param rows
	 * @param cols
	 * @param rowsInBlock
	 * @param colsInBlock
	 * @param sparsity
	 * @param min
	 * @param max
	 * @param seed
	 * @return
	 * @throws DMLRuntimeException
	 */
	/*public static void generateRandomMatrix(MatrixBlock out, String pdf, int rows, int cols, int rowsInBlock, int colsInBlock, double sparsity, double min, double max, long seed) 
		throws DMLRuntimeException
	{
		generateRandomMatrix(out, pdf, rows, cols, rowsInBlock, colsInBlock, sparsity, min, max, null, seed);
	}*/
	
	/**
	 * Function to generate a matrix of random numbers. This is invoked both
	 * from CP as well as from MR. In case of CP, it generates an entire matrix
	 * block-by-block. A <code>bigrand</code> is passed so that block-level
	 * seeds are generated internally. In case of MR, it generates a single
	 * block for given block-level seed <code>bSeed</code>.
	 * 
	 * When pdf="uniform", cell values are drawn from uniform distribution in
	 * range <code>[min,max]</code>.
	 * 
	 * When pdf="normal", cell values are drawn from standard normal
	 * distribution N(0,1). The range of generated values will always be
	 * (-Inf,+Inf).
	 * 
	 * @param rows
	 * @param cols
	 * @param rowsInBlock
	 * @param colsInBlock
	 * @param sparsity
	 * @param min
	 * @param max
	 * @param bigrand
	 * @param bSeed
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static void generateRandomMatrix(MatrixBlock out, String pdf, int rows, int cols, int rowsInBlock, int colsInBlock, long[] nnzInBlocks, double sparsity, double min, double max, Well1024a bigrand, long bSeed) throws DMLRuntimeException
	{
		boolean invokedFromCP = true;
		if(bigrand == null)
			invokedFromCP = false;
		
		// Setup Pseudo Random Number Generator for cell values based on 'pdf'.
		PRNGenerator valuePRNG = null;
		if ( pdf.equalsIgnoreCase(RAND_PDF_UNIFORM)) 
			valuePRNG = new UniformPRNGenerator();
		else if ( pdf.equalsIgnoreCase(RAND_PDF_NORMAL))
			valuePRNG = new NormalPRNGenerator();
		else
			throw new DMLRuntimeException("Unsupported distribution function for Rand: " + pdf);
		
		/*
		 * Setup min and max for distributions other than "uniform". Min and Max
		 * are set up in such a way that the usual logic of
		 * (max-min)*prng.nextDouble() is still valid. This is done primarily to
		 * share the same code across different distributions.
		 */
		if ( pdf.equalsIgnoreCase(RAND_PDF_NORMAL) ) {
			min=0;
			max=1;
		}
		
		// Determine the sparsity of output matrix
		// if invoked from CP: estimated NNZ is for entire matrix
		// if invoked from CP: estimated NNZ is for one block
		final long estnnz = (invokedFromCP ? (long)(sparsity * rows * cols) : nnzInBlocks[0]);
		boolean lsparse = MatrixBlock.evalSparseFormatInMemory( rows, cols, estnnz );
		out.reset(rows, cols, lsparse);
		
		// Special case shortcuts for efficiency
		if ( pdf.equalsIgnoreCase(RAND_PDF_UNIFORM)) {
			//specific cases for efficiency
			if ( min == 0.0 && max == 0.0 ) { //all zeros
				// nothing to do here
				return;
			} 
			else if( !out.sparse && sparsity==1.0d && min == max ) //equal values
			{
				out.allocateDenseBlock();
				Arrays.fill(out.denseBlock, 0, out.rlen*out.clen, min);
				out.nonZeros = out.rlen*out.clen;
				return;
			}
		}
		
		// Allocate memory
		if ( out.sparse ) {
			out.sparseRows = new SparseRow[rows];
			//note: individual sparse rows are allocated on demand,
			//for consistency with memory estimates and prevent OOMs.
		}
		else{
			out.allocateDenseBlock();	
		}

		double range = max - min;

		final int clen = out.clen;
		final int estimatedNNzsPerRow = out.estimatedNNzsPerRow;
		
		int nrb = (int) Math.ceil((double)rows/rowsInBlock);
		int ncb = (int) Math.ceil((double)cols/colsInBlock);
		int blockrows, blockcols, rowoffset, coloffset;
		int blocknnz;
		int blockID = 0;
		// loop through row-block indices
		for(int rbi=0; rbi < nrb; rbi++) {
			blockrows = (rbi == nrb-1 ? (rows-rbi*rowsInBlock) : rowsInBlock);
			rowoffset = rbi*rowsInBlock;
			
			// loop through column-block indices
			for(int cbj=0; cbj < ncb; cbj++, blockID++) {
				blockcols = (cbj == ncb-1 ? (cols-cbj*colsInBlock) : colsInBlock);
				coloffset = cbj*colsInBlock;
				
				// Generate a block (rbi,cbj) 
				
				// select the appropriate block-level seed
				long seed = -1;
				if ( !invokedFromCP ) {
					// case of MR: simply use the passed-in value
					seed = bSeed;
				}
				else {
					// case of CP: generate a block-level seed from matrix-level Well1024a seed
					seed = bigrand.nextLong();
				}
				// Initialize the PRNGenerator for cell values
				valuePRNG.init(seed);
				
				// Initialize the PRNGenerator for determining cells that contain a non-zero value
				// Note that, "pdf" parameter applies only to cell values and the individual cells 
				// are always selected uniformly at random.
				UniformPRNGenerator nnzPRNG = new UniformPRNGenerator(seed);
				
				// block-level sparsity, which may differ from overall sparsity in the matrix.
				// (e.g., border blocks may fall under skinny matrix turn point, in CP this would be 
				// irrelevant but we need to ensure consistency with MR)
				boolean localSparse = MatrixBlock.evalSparseFormatInMemory(blockrows, blockcols, nnzInBlocks[blockID] ); //(long)(sparsity*blockrows*blockcols));  
				
				if ( localSparse ) {
					SparseRow[] c = out.sparseRows;
					blocknnz = (int) nnzInBlocks[blockID]; //(int) Math.ceil((blockrows*sparsity)*blockcols);
					for(int ind=0; ind<blocknnz; ind++) {
						int i = nnzPRNG.nextInt(blockrows);
						int j = nnzPRNG.nextInt(blockcols);
						double val = min + (range * nnzPRNG.nextDouble());
						if( c[rowoffset+i]==null )
							c[rowoffset+i]=new SparseRow(estimatedNNzsPerRow, clen);
						c[rowoffset+i].set(coloffset+j, val);
					}
				}
				else {
					if (sparsity == 1.0) {
						double[] c = out.denseBlock;
						for(int ii=0; ii < blockrows; ii++) {
							for(int jj=0, index = ((ii+rowoffset)*cols)+coloffset; jj < blockcols; jj++, index++) {
								c[index] = min + (range * valuePRNG.nextDouble());
							}
						}
					}
					else {
						if ( out.sparse ) {
							/* This case evaluated only when this function is invoked from CP. 
							 * In this case:
							 *     sparse=true -> entire matrix is in sparse format and hence denseBlock=null
							 *     localSparse=false -> local block is dense, and hence on MR side a denseBlock will be allocated
							 * i.e., we need to generate data in a dense-style but set values in sparseRows
							 * 
							 */
							// In this case, entire matrix is in sparse format but the current block is dense
							SparseRow[] c = out.sparseRows;
							for(int ii=0; ii < blockrows; ii++) {
								for(int jj=0; jj < blockcols; jj++) {
									if(nnzPRNG.nextDouble() <= sparsity) {
										double val = min + (range * valuePRNG.nextDouble());
										if( c[ii+rowoffset]==null )
											c[ii+rowoffset]=new SparseRow(estimatedNNzsPerRow, clen);
										c[ii+rowoffset].set(jj+coloffset, val);
									}
								}
							}
						}
						else {
							double[] c = out.denseBlock;
							for(int ii=0; ii < blockrows; ii++) {
								for(int jj=0, index = ((ii+rowoffset)*cols)+coloffset; jj < blockcols; jj++, index++) {
									if(nnzPRNG.nextDouble() <= sparsity) {
										c[index] =  min + (range * valuePRNG.nextDouble());
									}
								}
							}
						}
					}
				} // sparse or dense 
			} // cbj
		} // rbi
		
		out.recomputeNonZeros();
	}
	
	/**
	 * Method to generate a sequence according to the given parameters. The
	 * generated sequence is always in dense format.
	 * 
	 * Both end points specified <code>from</code> and <code>to</code> must be
	 * included in the generated sequence i.e., [from,to] both inclusive. Note
	 * that, <code>to</code> is included only if (to-from) is perfectly
	 * divisible by <code>incr</code>.
	 * 
	 * For example, seq(0,1,0.5) generates (0.0 0.5 1.0) 
	 *      whereas seq(0,1,0.6) generates (0.0 0.6) but not (0.0 0.6 1.0)
	 * 
	 * @param from
	 * @param to
	 * @param incr
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static void generateSequence(MatrixBlock out, double from, double to, double incr) 
		throws DMLRuntimeException 
	{
		boolean neg = (from > to);
		if (neg != (incr < 0))
			throw new DMLRuntimeException("Wrong sign for the increment in a call to seq()");
		
		//System.out.println(System.nanoTime() + ": begin of seq()");
		int rows = 1 + (int)Math.floor((to-from)/incr);
		int cols = 1;
		out.sparse = false; // sequence matrix is always dense
		out.reset(rows, cols, out.sparse);
		
		out.allocateDenseBlock();
		
		//System.out.println(System.nanoTime() + ": MatrixBlockDSM.seq(): seq("+from+","+to+","+incr+") rows = " + rows);
		double[] c = out.denseBlock; 
		
		c[0] = from;
		for(int i=1; i < rows; i++) {
			from += incr;
			c[i] = from;
		}
		
		out.recomputeNonZeros();
		//System.out.println(System.nanoTime() + ": end of seq()");
	}
	

	public static void main(String[] args) {
		long nrow = 150093;
		long ncol = 298900;
		double sparsity = 9.44796E-07;
		
		long[] blockNNZs = computeNNZperBlock(nrow, ncol, 1000, 1000, sparsity);
		
		System.out.println("Number of blocks = " + blockNNZs.length);
		long nnz = 0;
		for(int i=0; i < blockNNZs.length; i++){
			System.out.print(blockNNZs[i] + ",");
			nnz += blockNNZs[i];
		}
		System.out.println("\n" + "Total NNZ = " + nnz + " (expected = " + (nrow*(ncol*sparsity)) + ")");
		
	}
	
}