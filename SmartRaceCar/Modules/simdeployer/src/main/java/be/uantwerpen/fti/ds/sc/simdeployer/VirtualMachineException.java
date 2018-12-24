package be.uantwerpen.fti.ds.sc.simdeployer;

public class VirtualMachineException extends Exception
{
	public VirtualMachineException(String s)
	{
		super(s);
	}

	public VirtualMachineException(String s, Throwable throwable)
	{
		super(s, throwable);
	}

	@Override
	public String getMessage()
	{
		return super.getMessage();
	}

	@Override
	public void printStackTrace()
	{
		super.printStackTrace();
	}

	@Override
	public StackTraceElement[] getStackTrace()
	{
		return super.getStackTrace();
	}

	@Override
	public void setStackTrace(StackTraceElement[] stackTraceElements)
	{
		super.setStackTrace(stackTraceElements);
	}
}