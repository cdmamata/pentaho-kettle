/*************************************************************************************** 
 * Copyright (C) 2007 Samatar, Brahim.  All rights reserved. 
 * This software was developed by Samatar, Brahim and is provided under the terms 
 * of the GNU Lesser General Public License, Version 2.1. You may not use 
 * this file except in compliance with the license. A copy of the license, 
 * is included with the binaries and source code. The Original Code is Samatar, Brahim.  
 * The Initial Developer is Samatar, Brahim.
 *
 * Software distributed under the GNU Lesser Public License is distributed on an 
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. 
 * Please refer to the license for the specific language governing your rights 
 * and limitations.
 ***************************************************************************************/

package org.pentaho.di.trans.steps.getxmldata;

import java.io.FileInputStream;
import java.io.StringReader;
import java.util.List;
import java.util.HashMap;

import org.dom4j.io.SAXReader;
import org.dom4j.Document;
import org.dom4j.XPath;
import org.dom4j.tree.AbstractNode;

import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileType;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.ResultFile;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.fileinput.FileInputList;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

/**
 * Read XML files, parse them and convert them to rows and writes these to one or more output 
 * streams.
 * 
 * @author Samatar,Brahim
 * @since 20-06-2007
 */
public class getXMLData extends BaseStep implements StepInterface
{
	private getXMLDataMeta meta;
	private getXMLDataData data;
	
	public getXMLData(StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta, Trans trans)
	{
		super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
	}
	
	public boolean processRow(StepMetaInterface smi, StepDataInterface sdi) throws KettleException
	{
		Object[] row;
		boolean sendToErrorRow=false;
		String errorMessage = null;
		
		row = getRow();       // Get row from input rowset & set row busy!
		 
		if(!meta.getIsInFields())	
		{
			if (data.filenr >= data.files.nrOfFiles())
	        {
	            setOutputDone();
	            return false;
	        }
		}
		else
		{			
			if(row==null)
			{
			      setOutputDone();
		           return false;
			}
		}
		
		if(first)
		{
			first=false;
			
			 // Create the output row meta-data
            data.outputRowMeta = new RowMeta();

			meta.getFields(data.outputRowMeta, getStepname(), null, null, this);
			
			// Create convert meta-data objects that will contain Date & Number formatters
            data.convertRowMeta = data.outputRowMeta.clone();
            for (int i=0;i<data.convertRowMeta.size();i++) data.convertRowMeta.getValueMeta(i).setType(ValueMetaInterface.TYPE_STRING);
  
            // For String to <type> conversions, we allocate a conversion meta data row as well...
			//
			data.convertRowMeta = data.outputRowMeta.clone();
			for (int i=0;i<data.convertRowMeta.size();i++) {
				data.convertRowMeta.getValueMeta(i).setType(ValueMetaInterface.TYPE_STRING);            
			}
			
			if(meta.getIsInFields())
			{
				// Check is XML field is provided
				if (Const.isEmpty(meta.getXMLField()))
				{
					logError(Messages.getString("getXMLData.Log.NoField"));
					throw new KettleException(Messages.getString("getXMLData.Log.NoField"));
				}
				
				// cache the position of the field			
				if (data.indexOfXmlField<0)
				{
					data.indexOfXmlField =getInputRowMeta().indexOfValue(meta.getXMLField());
					if (data.indexOfXmlField<0)
					{
						// The field is unreachable !
						logError(Messages.getString("getXMLData.Log.ErrorFindingField")+ "[" + meta.getXMLField()+"]"); //$NON-NLS-1$ //$NON-NLS-2$
						throw new KettleException(Messages.getString("getXMLData.Exception.CouldnotFindField",meta.getXMLField())); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}				
			}	
			else
			{
				// XML source is file (probably many files)...
				handleMissingFiles();
			}
			
		}
		try
		{
			if (meta.getIsInFields())
			{	
				// get XML field value
				String Fieldvalue= getInputRowMeta().getString(row,data.indexOfXmlField);
				
				if(log.isDetailed()) log.logDetailed(toString(),Messages.getString("getXMLData.Log.XMLStream", meta.getXMLField(),Fieldvalue));
				
				if(meta.getIsAFile())
				{
					FileObject file=null;
					try
					{
						// XML source is a file.
						file=  KettleVFS.getFileObject(Fieldvalue);
						// Process file ...
						processXML(file, null, false, row);						
					}
					catch (Exception e)
					{
						// Something to do here?
					}finally{
						try
						{if(file!=null) file.close();}catch (Exception e){}
					}
				}
				else
				{
					// Let's parse the XML stream
					processXML(null,Fieldvalue , true,row);
				}
			}		
			else
			{
				// XML source is a file (probably many files...)
				data.rownr=0;
	
					for (int i=0;i<data.files.nrOfFiles();i++)
					{			
						if ((meta.getRowLimit()>0 &&  data.rownr<meta.getRowLimit()) || meta.getRowLimit()==0) 
						{			
							data.file = (FileObject) data.files.getFile(i);	    	
					    	logBasic(Messages.getString("getXMLData.Log.OpeningFile", data.file.toString()));					
					    	
							// Fetch files and process each one
							processXML(data.file, null, false, null);	
							
							if (log.isDetailed()) logDetailed(Messages.getString("getXMLData.Log.FileOpened", data.file.toString()));      				
						}
						if(meta.addResultFile())
						{
							//	Add this to the result file names...
							ResultFile resultFile = new ResultFile(ResultFile.FILE_TYPE_GENERAL, data.file, getTransMeta().getName(), getStepname());
							resultFile.setComment(Messages.getString("getXMLData.Log.FileAddedResult"));
							addResultFile(resultFile);
						}
				    	
						// 	 Move file pointer ahead!
						data.filenr++;					
					}
				
				setOutputDone();  // signal end to receiver(s)
				// This is the end of this step. 
				 return false;			
			}	
		}
		catch (KettleStepException k)
		{			
			if (getStepMeta().isDoingErrorHandling())
			{
		          sendToErrorRow = true;
		          errorMessage = k.toString();
			}
			else
			{
				if (meta.getIsInFields())
					logError("Error : " + k.toString());
				else
					logError(Messages.getString("getXMLData.Log.UnableToOpenFile", ""+data.filenr, data.file.toString(), k.toString()));
				setErrors(1);
				stopAll();
				setOutputDone();
				return false;
				
			}
			if (sendToErrorRow)
			{
			   // Simply add this row to the error row
			   putError(getInputRowMeta(), row, 1, errorMessage, null, "GetXMLData001");
			}
		}
		 return true;		  
	}
	private void handleMissingFiles() throws KettleException
	{
		List<FileObject> nonExistantFiles = data.files.getNonExistantFiles();
	
		if (nonExistantFiles.size() != 0)
		{
			String message = FileInputList.getRequiredFilesDescription(nonExistantFiles);
			log.logBasic("Required files", "WARNING: Missing " + message);

			throw new KettleException("Following required files are missing " +message);
		}

		List<FileObject> nonAccessibleFiles = data.files.getNonAccessibleFiles();
		if (nonAccessibleFiles.size() != 0)
		{
			String message = FileInputList.getRequiredFilesDescription(nonAccessibleFiles);
			log.logBasic("Required files", "WARNING: Not accessible " + message);

				throw new KettleException("Following required files are not accessible " +message);
		}
	}
	
	/**
	 * Build an empty row based on the meta-data.
	 * 
	 * @return
	 */
	private Object[] buildEmptyRow()
	{
        Object[] rowData = RowDataUtil.allocateRowData(data.outputRowMeta.size());
 
	    return rowData;
	}
	
	/**
	 * Main function of the processing.
	 */
	private void processXML(FileObject file ,String StringXML,boolean IsInXMLField,Object[] row) 
	    throws KettleStepException
	{	
		Object[] outputRowData = null;
		 boolean sendToErrorRow=false;
		 String errorMessage = null;
		
		try 
		{		
			if(meta.getIsInFields() && meta.getIsAFile())
			{
				// Check if file exists !
				if(file.exists())
				{
					if(file.getType() == FileType.FILE)
					{
						// it's  a file
						if (log.isDetailed()) log.logDetailed(toString(),Messages.getString("getXMLData.Log.IsAFile",file.toString()));
					}
					else
					{
						// it's not a file
						log.logError(toString(),Messages.getString("getXMLData.Log.IsNotAFile",file.toString()));
						throw new KettleException(Messages.getString("getXMLData.Log.IsNotAFile",file.toString()));					
					}
				}
				else
				{
					// We can not find file ..
					log.logError(toString(),Messages.getString("getXMLData.Log.WeCanFindFile",file.toString()));
					throw new KettleException(Messages.getString("getXMLData.Log.WeCanFindFile",file.toString()));
				}
			}
			// get encoding. By default UTF-8
			String encoding="UTF-8";
			if (!Const.isEmpty(meta.getEncoding()))
			{
				encoding=meta.getEncoding();
			}

			SAXReader reader = new SAXReader();
			// Validate XML against specified schema?
			if(meta.isValidating())
			{
				reader.setValidation(true);
				reader.setFeature("http://apache.org/xml/features/validation/schema", true);
			}

			Document doc = null;
			HashMap xmlNameSpaceMap = new HashMap();
		
			if (log.isDetailed()) log.logDetailed(toString(), Messages.getString("getXMLData.Log.CreateDocumentStart"));
			
			if (IsInXMLField)
			{
				doc= reader.read(new StringReader(StringXML));	
			}
			else
			{
				doc = reader.read(new FileInputStream(KettleVFS.getFilename(file)));
				doc.setXMLEncoding(encoding);
			}
			
		
 	
			if (log.isDetailed()) log.logDetailed(toString(), Messages.getString("getXMLData.Log.CreateDocumentEnd"));
			
	        XPath xpath = doc.createXPath(environmentSubstitute(meta.getLoopXPath()));
	        
	        /* Set Name space aware?*/
	    	if(meta.isNamespaceAware())
			{
				String ns = doc.getRootElement().getNamespaceURI();
				xmlNameSpaceMap.put("ns", ns);
				xpath.setNamespaceURIs(xmlNameSpaceMap);
			}
	        
			List<AbstractNode> an = (List<AbstractNode>) xpath.selectNodes(doc);
			if (IsInXMLField)
	        {
	        	if (log.isDetailed()) logDetailed(Messages.getString("getXMLData.Log.LoopOccurences",""+an.size()));	
	        }
	        else
	        {
	        	if (log.isDetailed()) logDetailed(Messages.getString("getXMLData.Log.LoopFileOccurences",""+an.size(),KettleVFS.getFilename(file)));
	        }

			for (AbstractNode a : an) 
			{
				if ((meta.getRowLimit()>0 && data.rownr<meta.getRowLimit()) || meta.getRowLimit()==0)  
				{
					
					// Create new row				
					outputRowData = buildEmptyRow();
					
					// Clone previous row ...
					if(row!=null) outputRowData = row.clone();
					
					// Read fields...
					for (int i=0;i<meta.getInputFields().length;i++)
					{	
						// Get field
						getXMLDataField xmlInputField = meta.getInputFields()[i];
						// Get the Path to look for
						String XPathValue = environmentSubstitute(xmlInputField.getXPath());
						// Get the path type
						String Element_Type = xmlInputField.getElementTypeCode();
						
						int indexvarstart=XPathValue.indexOf("{$");
						int indexvarend=XPathValue.indexOf('}');
						if(indexvarstart>=0 && indexvarend>=0)
						{
							String NameVarInputField = XPathValue.substring(indexvarstart+2, indexvarend);
						
							for (int k=0;k<meta.getInputFields().length;k++)
							{
								getXMLDataField Tmp_xmlInputField = meta.getInputFields()[k];
								if(Tmp_xmlInputField.getName().equalsIgnoreCase(NameVarInputField))
								{		
									XPathValue = XPathValue.replaceAll("\\{\\$"+NameVarInputField+"\\}","'"+ outputRowData[k]+"'");
									if ( log.isDetailed() )
									{
									   if(log.isDetailed()) log.logDetailed(toString(),XPathValue);
									}
								}
							}								
						}
				

						// Get node value
						String value =null;
						if (!Element_Type.equals("node")) XPathValue='@'+XPathValue;
						XPath xpathnode = a.createXPath(XPathValue);
						
					
						if(xpathnode!=null) value = xpathnode.valueOf(a);
						
						// Do trimming
						switch (meta.getInputFields()[i].getTrimType())
						{
						case getXMLDataField.TYPE_TRIM_LEFT:
							value = Const.ltrim(value);
							break;
						case getXMLDataField.TYPE_TRIM_RIGHT:
							value = Const.rtrim(value);
							break;
						case getXMLDataField.TYPE_TRIM_BOTH:
							value = Const.trim(value);
							break;
						default:
							break;
						}

						// Do conversions
						//
						ValueMetaInterface targetValueMeta = data.outputRowMeta.getValueMeta(i);
						ValueMetaInterface sourceValueMeta = data.convertRowMeta.getValueMeta(i);
						outputRowData[i] = targetValueMeta.convertData(sourceValueMeta, value);
						
						// Do we need to repeat this field if it is null?
						if (meta.getInputFields()[i].isRepeated())
						{
							if (data.previousRow!=null && Const.isEmpty(value))
							{
								outputRowData[i] = data.previousRow[i];
							}
						}

					}// End of loop over fields...	
					int rowIndex = meta.getInputFields().length;
					
					// See if we need to add the filename to the row...
					if ( meta.includeFilename() && !Const.isEmpty(meta.getFilenameField()) ) {
						outputRowData[rowIndex++] = KettleVFS.getFilename(data.file);
					}
					 // See if we need to add the row number to the row...  
			        if (meta.includeRowNumber() && !Const.isEmpty(meta.getRowNumberField()))
			        {
			            outputRowData[rowIndex++] = new Long(data.rownr);
			        }
					
					RowMetaInterface irow = getInputRowMeta();
					
					data.previousRow = irow==null?outputRowData:(Object[])irow.cloneRow(outputRowData); // copy it to make
					// surely the next step doesn't change it in between...
					data.rownr++;
		           
					putRow(data.outputRowMeta, outputRowData);  // copy row to output rowset(s);
				}
			}        
		} 
		catch(Exception e)
		{
			if (getStepMeta().isDoingErrorHandling())
			{
		          sendToErrorRow = true;
		          errorMessage = e.toString();
			}
			else
			{
				log.logError(toString(), e.toString());
				throw new KettleStepException(e.toString());
			}

			if (sendToErrorRow)
			{
			   // Simply add this row to the error row
			   putError(getInputRowMeta(), row, 1, errorMessage, null, "getXMLData001");
			}
		} 			
	}

	public boolean init(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta=(getXMLDataMeta)smi;
		data=(getXMLDataData)sdi;				
		
		if (super.init(smi, sdi))
		{
			if(!meta.getIsInFields())
			{
				data.files = meta.getFiles(this);
				if (data.files.nrOfFiles() == 0 && data.files.nrOfMissingFiles() == 0)
				{
					logError(Messages.getString("getXMLData.Log.NoFiles"));
					return false;
				}
	            
				data.rownr = 1L;
			}			
				
			return true;
		}
		return false;		
	}
	
	public void dispose(StepMetaInterface smi, StepDataInterface sdi)
	{
		meta=(getXMLDataMeta)smi;
		data=(getXMLDataData)sdi;
		super.dispose(smi, sdi);
	}	
	
	//
	// Run is were the action happens!	
	public void run()
	{			    
		try
		{
			logBasic(Messages.getString("System.Log.StartingToRun")); //$NON-NLS-1$
			
			while (processRow(meta, data) && !isStopped());
		}
		catch(Throwable t)
		{
			logError(Messages.getString("System.Log.UnexpectedError")+" : "); //$NON-NLS-1$ //$NON-NLS-2$
			logError(Const.getStackTracker(t));
			setErrors(1);
			stopAll();
		}
		finally
		{
			dispose(meta, data);
			logSummary();
			markStop();
		}
	}
}