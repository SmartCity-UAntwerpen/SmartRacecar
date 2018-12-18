package be.uantwerpen.fti.ds.sc.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Help model to deal with REST client requests. Uses Jersey library.
 */
public class RESTUtils
{
	private Logger log;

	private WebTarget webTarget; // URL to the domain of the REST service that is being connected to.


	/**
	 * Help model to deal with REST client requests. Uses Jersey library.
	 *
	 * @param URL to the REST service that is being connected to.
	 */
	public RESTUtils(String URL)
	{
		this.log = LoggerFactory.getLogger(this.getClass());

		Client client = ClientBuilder.newClient();
		this.webTarget = client.target(URL);
	}

	/**
	 * REST GET request to receive a response of the type Text Plain.
	 *
	 * @param URL Path of the GET request.
	 * @return REST response of the type Text Plain.
	 */
	public String getTextPlain(String URL)
	{
		WebTarget resourceWebTarget = this.webTarget.path(URL);
		Invocation.Builder invocationBuilder = resourceWebTarget.request("text/plain");
		Response response = null;
		this.log.info("Attempting GET(text plain) request with URL:" + resourceWebTarget.getUri());
		try
		{
			response = invocationBuilder.get();
		} catch (ProcessingException e)
		{
			this.log.error("Cannot connect to REST service: " + e);
			System.exit(0); //make sure the correct mode is selected (debugwithoutBackbone/debugwithoutMAAS)
		}
		checkForError(response, resourceWebTarget.getUri());
		String responseString = response.readEntity(String.class);
		this.log.info("Text Returned from request '" + URL + "' is: " + responseString);
		return responseString;
	}

	/**
	 * REST GET request. Doesn't expect any return.
	 *
	 * @param URL Path of the GET request.
	 */
	public void getCall(String URL)
	{
		WebTarget resourceWebTarget = this.webTarget.path(URL);
		Invocation.Builder invocationBuilder = resourceWebTarget.request("text/plain");
		Response response = null;
		this.log.info("Attempting GET request with URL:" + resourceWebTarget.getUri());
		try
		{
			response = invocationBuilder.get();
		} catch (ProcessingException e)
		{
			this.log.error("Cannot connect to REST service: " + e);
			System.exit(0);
		}
		checkForError(response, resourceWebTarget.getUri());
	}

	/**
	 * REST GET request to receive a response of the type JSON.
	 *
	 * @param URL Path of the GET request.
	 * @return REST response of the type JSON.
	 */
	public String getJSON(String URL)
	{
		WebTarget resourceWebTarget = this.webTarget.path(URL);
		Invocation.Builder invocationBuilder = resourceWebTarget.request("application/json");
		Response response = null;
		this.log.info("Attempting GET(JSON) request with URL:" + resourceWebTarget.getUri());
		try
		{
			response = invocationBuilder.get();
		} catch (ProcessingException e)
		{
			this.log.error("Cannot connect to REST service: " + e);
			System.exit(0);
		}
		checkForError(response, resourceWebTarget.getUri());
		String responseString = response.readEntity(String.class);
		this.log.info("JSON Returned from request '" + URL + "' is: " + responseString);
		return responseString;
	}

	/**
	 * REST POST request of the type JSON to receive a response of the type JSON.
	 *
	 * @param URL        Path of the GET request.
	 * @param jsonString The JSON string to be posted.
	 * @return REST response of the type JSON.
	 */
	public String postJSONGetJSON(String URL, String jsonString) throws IOException
	{
		WebTarget resourceWebTarget = this.webTarget.path(URL);
		Invocation.Builder invocationBuilder = resourceWebTarget.request("application/json");
		this.log.info("Attempting POST(JSON) request with URL:" + resourceWebTarget.getUri() + " and with json:" + jsonString);
		Response response = null;
		try
		{
			response = invocationBuilder.put(Entity.json(jsonString));
		} catch (ProcessingException e)
		{
			String errorString = "Cannot connect to REST service (URL: \"" + resourceWebTarget.getUri() + "\": ";
			this.log.error(errorString, e);
			throw new IOException(errorString);
		}
		checkForError(response, resourceWebTarget.getUri());
		String responseString = response.readEntity(String.class);
		this.log.info("JSON Returned from request '" + URL + "' is: " + responseString);
		return responseString;
	}

	/**
	 * Send a HTTP POST request to the specified URL.
	 * The request's body is empty.
	 * @param URL
	 * @return
	 */
	public String postEmpty(String URL)
	{
		this.log.debug("Attempting POST request with URL: \"" + URL + "\"");

		WebTarget resourceWebTarget = this.webTarget.path(URL);

		Invocation.Builder invocationBuilder = resourceWebTarget.request();

		Response response = invocationBuilder.post(Entity.text(""));

		checkForError(response, resourceWebTarget.getUri());

		String responseString = response.readEntity(String.class);

		this.log.debug("POST Request got response: \"" + responseString + "\"");

		return responseString;
	}

	/**
	 * REST GET request of the receive a response of the Octet-stream to download a file.
	 *
	 * @param URL           Path of the GET request.
	 * @param folder        The path to download the file towards.
	 * @param fileName      Filename the downloaded file should get.
	 * @param fileExtention File extention the downloaded file should get.
	 */
	public void getFile(String URL, String folder, String fileName, String fileExtention)
	{
		java.nio.file.Path out = Paths.get(folder + "/" + fileName + "." + fileExtention);
		WebTarget resourceWebTarget = this.webTarget.path(URL);
		Invocation.Builder invocationBuilder = resourceWebTarget.request("application/octet-stream");
		Response response;
		this.log.info("Attempting GET(file) request with URL:" + resourceWebTarget.getUri());
		try
		{
			response = invocationBuilder.get();
			checkForError(response, resourceWebTarget.getUri());
			InputStream in = response.readEntity(InputStream.class);
			Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
			in.close();
			this.log.info("Downloaded file '" + fileName + "." + fileExtention + "'.");
		} catch (ProcessingException e)
		{
			this.log.error("Cannot connect to REST service: " + e);
			System.exit(0);
		} catch (IOException e)
		{
			this.log.error("Could not store file '" + fileName + "." + fileExtention + "' after download: " + e);
		}
	}

	/**
	 * Help function to analyze the REST response to get the HTTP status and check which code it was. If it was an
	 * error code this method will throw the right exception.
	 *
	 * @param response The REST response.
	 * @param url      The URL that was being reached.
	 */
	private void checkForError(Response response, java.net.URI url)
	{
		if (response.getStatus() != Response.Status.OK.getStatusCode())
		{
			Response.Status status = Response.Status.fromStatusCode(response.getStatus());
			WebApplicationException webAppException;
			switch (status)
			{
				case BAD_REQUEST:
					webAppException = new BadRequestException(response.readEntity(String.class));
					break;
				case FORBIDDEN:
					webAppException = new ForbiddenException(response.readEntity(String.class));
					break;
				case NOT_FOUND:
					webAppException = new NotFoundException(response.readEntity(String.class));
					break;
				case NOT_ACCEPTABLE:
					webAppException = new NotAcceptableException(response.readEntity(String.class));
					break;
				case UNSUPPORTED_MEDIA_TYPE:
					webAppException = new NotSupportedException(response.readEntity(String.class));
					break;
				case INTERNAL_SERVER_ERROR:
					webAppException = new InternalServerErrorException(response.readEntity(String.class));
					break;
				case SERVICE_UNAVAILABLE:
					webAppException = new ServiceUnavailableException(response.readEntity(String.class));
					break;
				default:
					webAppException = new WebApplicationException(response.readEntity(String.class));
					break;
			}

			this.log.error("Error with request for URL:" + url, webAppException);
			throw webAppException;
		}
	}
}
