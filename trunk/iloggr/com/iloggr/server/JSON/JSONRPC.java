/**
 * Copyright (C) 2009 Bump Mobile Inc.
 * All rights reserved.
 */
package com.iloggr.server.JSON;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.inject.internal.collect.Maps;
import com.iloggr.client.model.Account;
import com.iloggr.client.model.Application;
import com.iloggr.client.model.Carrier;
import com.iloggr.client.model.Counter;
import com.iloggr.client.model.Event;
import com.iloggr.client.model.LocationFix;
import com.iloggr.client.model.Phone;
import com.iloggr.client.model.Provisioning;
import com.iloggr.client.model.ProvisioningParameter;
import com.iloggr.client.model.iLoggrObject;
import com.iloggr.client.services.AccountService;
import com.iloggr.client.services.ProvisioningService;
import com.iloggr.client.services.RecordService;
import com.iloggr.client.services.ReportingService;
import com.iloggr.gwt.util.client.ILException;

//////////////////////////////////////////////////////////////////////////////////
//
//  JSON RPC Objects are packed as follows (resembles json-rpc specification)
//
//  Request: {"method" : "service-method-name", "parameters" : [parameter0, parameter1, parameter2, ...]}
//  Parameter: { "__jsonclass__" : "class-nickname", fieldname : parameter, fieldname : parameter, "value" : value }
//
//  All object properties are wrapped by parameter objects so the type is explicitly declared in the JSON object
//
/////////////////////////////////////////////////////////////////////////////////

/**
 * Main JSON serialize/deserialize class to turn java objects into JSON and parse incoming JSON parameters into
 * their corresponding java object types.
 * <br><br>
 * RPC call format resembles the json-rpc specification:
 * <br><br>
 * Request: {"method" : "service-method-name", "parameters" : [parameter0, parameter1, parameter2, ...]}
 * Parameter: { "__jsonclass__" : "class-nickname", fieldname : parameter, fieldname : parameter, "value" : value }
 *<br><br>
 * All object properties are wrapped by parameter objects so the type is explicitly declared in the JSON object
 *
 * Returns a JSON object with the following properties:
 *
 *   value:  An embedded JSON object with the returned value or null if method was void
 *   error:  An error code mostly generated by ILException.  0 is success, < 0 is failure
 *   errorMessage: An error message
 *
 * @author eliot
 * @version 1.0
 * @see JSONRPCRequest
 *
 */
public class JSONRPC {

	// Populate the types
	// (Could use java class reflection capabilities but will filter based
	// on names below)
	private static final ImmutableMap<String, Class<?>> objectTypes =
		new ImmutableMap.Builder<String, Class<?>>()
			.put("Integer", Integer.TYPE)
			.put("Date", java.util.Date.class)
			.put("String", String.class)
			.put("Long", Long.TYPE)
			.put("Double", Double.TYPE)
			.put("Boolean", Boolean.TYPE)
			.put("Event", Event.class)
			.put("Account", Account.class)
			.put("Application", Application.class)
			.put("HashSet", HashSet.class)
			.put("Carrier", Carrier.class)
			.put("Phone", Phone.class)
			.put("Provisioning", Provisioning.class)
			.put("ProvisioningParameter", ProvisioningParameter.class)
			.put("LocationFix", LocationFix.class)
			.put("Counter", Counter.class)
			.build();

	private static final ImmutableMap<String, Class<?>> serviceInterfaces =
		buildServiceMethodMap(AccountService.class,
				ProvisioningService.class,
				RecordService.class,
				ReportingService.class);

	private static ImmutableMap<String, Class<?>> buildServiceMethodMap(Class<?>... services) {
		Map<String, Class<?>> serviceMethods = Maps.newHashMap();
		for (Class<?> service : services) {
			Preconditions.checkArgument(service.isInterface());
			for (Method serviceMethod : service.getDeclaredMethods()) {
				serviceMethods.put(serviceMethod.getName(), service);
			}
		}
		return ImmutableMap.copyOf(serviceMethods);
	}

	// This version names the service explicitly
	public static JSONRPCRequest decodeRequest(String payload) throws Exception {
		if (payload == null || payload.trim().equals("")) {
			throw new ILException(ILException.RECEIVED_EMPTY_MESSAGE_PAYLOAD);
		}
		int paramCount;
		JSONObject request;

		// Begin parsing
		try {
			request = JSONObject.fromObject(payload);
		} catch (Exception e) {
			throw new ILException(ILException.MESSAGE_PARSE_ERROR);
		}
		if (request == null) {
			throw new ILException(ILException.IMPROPERLY_FORMED_REQUEST);
		}

		String methodName = request.getString("method");
		Class<?> srvc = serviceInterfaces.get(methodName);
		if (srvc == null) {
			throw new ILException(ILException.INVALID_SERVICE_REQUEST);
		}

		// Get the parameters
		JSONArray pa = request.getJSONArray("parameters");
		paramCount = pa.size();
		Class<?>[] parameterTypes = new Class<?>[paramCount];
		Object[] parameterObjects = new Object[paramCount];
		for (int i = 0; i < paramCount; i++) {
			try  {
				parameterTypes[i] = getParameterType(pa.getJSONObject(i));
				parameterObjects[i] = parseParameter(pa.getJSONObject(i));
			} catch (Exception e) {
				throw new ILException(ILException.BAD_PARAMETER_TYPE);
			}
		}

		// Create new request object, send it back
		return new JSONRPCRequest(srvc, methodName, parameterTypes, parameterObjects);
	}

	public static Object parseParameter(JSONObject parameter) {
		// Each parameter has property "value"
		if (parameter == null || parameter.isNullObject())
			return null;
		Object v = parameter.get("value");
		if (v.getClass().equals(JSONObject.class) && ((JSONObject)v).isNullObject()) return null;
		Class<?> clazz = null;
		try {
			clazz = getParameterType(parameter);
		} catch (Exception e) {
			return null;
		}
		if (clazz == Double.TYPE) {
			return parameter.getDouble("value");
		} else if (clazz == Integer.TYPE) {
			return parameter.getInt("value");
		} else if (clazz == Long.TYPE) {
			return parameter.getLong("value");
		} else if (clazz == String.class) {
			return parameter.getString("value");
		} else if (clazz == Boolean.TYPE) {
			return parameter.getBoolean("value");
		} else if (clazz == Date.class) {
			String sValue = parameter.getString("value");
			if (sValue == null)
				return null;
			try {
				return getDateFormat().parse(sValue, new ParsePosition(0));
			} catch (Exception e) {
				return null;
			}
		} else if (clazz == HashSet.class) {
			JSONArray ja = parameter.getJSONArray("value");
			HashSet<Object> hsResult = new HashSet<Object>();
			for (int i = 0; i < ja.size(); i++) {
				JSONObject elem = ja.getJSONObject(i);
				// Recursive call
				hsResult.add(parseParameter(elem));
			}
			return hsResult;
		} else // complex object type
			try {
				return parseObject(parameter);
			} catch (Exception e) {
				// just ignore it for now, will put null into array
			}

		return null;
	}


	/**
	 * Parameters are always JSON objects with a "__jsonclass__" property that contains the name.
	 */
	public static Class<?> getParameterType(JSONObject param) throws Exception {
		if (param == null)
			throw new Exception("null parameter passed");
		String jcs = param.getString("__jsonclass__");
		if (jcs == null)
			throw new Exception("Parameter has no class");
		return objectTypes.get(jcs);
	}

	public static Object parseObject(JSONObject parameter) {
		Class<?> clazz;
		try {
			clazz = getParameterType(parameter);
			Object result = clazz.newInstance();
			Field[] fields = clazz.getDeclaredFields();
			for (Object key : parameter.keySet()) {
				String keyname = (String) key;
				for (Field f : fields) {
					// if there is a field name on the class of object that matches the parameter
					// field name in the JSON object
					if (f.getName().equalsIgnoreCase(keyname)) {
					    //  Either value is an object or string
						JSONObject value = parameter.getJSONObject(keyname);
						if (value != null) {
							f.set(result, parseParameter(value));
						} else {
							f.set(result, value);
						}
					}
				}
			}
			return result;
		} catch (Exception e) {
			return null;
		}
	}

	public static JSONObject encodeGeneralFailureResponse(Exception e) {
		JSONRPCResponse response = new JSONRPCResponse(e);
		return response.encode();
	}

	public static JSONObject encode(int value) {
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Integer");
		result.put("value", value);
		return JSONObject.fromObject(result);
	}

	public static String encode(String value) {
		return value;
	}

	public static JSONObject encodeStringObject(String value) {
		Map<String, Object> result = Maps.newHashMap();
		result.put("value",  value);
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(long value) {
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Long");
		result.put("value",  value);
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(double value) {
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Double");
		result.put("value", value);
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(boolean value) {
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Boolean");
		if (value) result.put("value", "true");
		else result.put("value", "false");
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(Integer value) {
		if (value == null) return null;
		return encode(value.intValue());
	}

	public static JSONObject encode(Long value) {
		if (value == null) return null;
		return encode(value.longValue());
	}

	public static JSONObject encode(Double value) {
		if (value == null) return null;
		return encode(value.doubleValue());
	}

	public static JSONObject encode(Boolean value) {
		if (value == null) return null;
		return encode(value.booleanValue());
	}

	public static JSONObject encode(Carrier carrier) {
		if (carrier == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Carrier");
		try {
			result.put("id", encode(carrier.getId()));
			result.put("name", encode(carrier.getName()));
			result.put("gateway", encode(carrier.getTextGateway()));
		} catch (Exception e) {
			//  Skip it, send a partial object
		}
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(Event event) {
		if (event == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Event");
		try {
			result.put("id", encode(event.getId()));
			result.put("recordTime", encode(event.getRecordTime()));
			result.put("description", encode(event.getDescription()));
			result.put("data", encode(event.getData()));
			result.put("latitude", event.getLatitude());
			result.put("longitude", event.getLongitude());
			result.put("application", encode(event.getApplication()));
		} catch (Exception e) {
			//  Skip it, send a partial object
		}
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(LocationFix lf) {
		if (lf == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "LocationFix");
		try {
			result.put("id", encode(lf.getId()));
			result.put("latitude", encode(lf.getLatitude()));
			result.put("longitude", encode(lf.getLongitude()));
			result.put("accuracy", encode(lf.getAccuracy()));
			result.put("timeOfFix", encode(lf.getTimeOfFix()));
		} catch (Exception e) {
			//  Skip it, send a partial object
		}
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(Phone phone) {
		if (phone == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		result.put("__jsonclass__", "Phone");
		try {
			result.put("id", encode(phone.getId()));
			result.put("clientID", encode(phone.getClientID()));
			result.put("version", encode(phone.getVersion()));
		} catch (Exception e) {
			//  Skip it, send a partial object
		}
		return JSONObject.fromObject(result);
	}

	/**
	 * Sends back a map of parameters.
	 */
	public static JSONObject encode(Provisioning prov) {
		if (prov == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		try {
			result.put("__jsonclass__", "Provisioning");
			for (ProvisioningParameter p : prov.getParameters()) {
				if (p.isActive()) result.put(p.getName(),p.getValue());
			}
		} catch (Exception e) {
			//  Skip it, send a partial object
		}
		return JSONObject.fromObject(result);
	}

	/**
	 * Sends it back as a ProvisioningParameter object
	 */
	public static JSONObject encode(ProvisioningParameter param) {
		if (param == null) return null;
		// if not active, don't transmit
		if (!param.isActive()) return null;
		Map<String, Object> result = Maps.newHashMap();
		try {
			// Just need to send name, value, and type to iPhone
			result.put("__jsonclass__", "ProvisioningParameter");
			result.put("name", encode(param.getName()));
			result.put("value", encode(param.getValue()));
			result.put("type", encode(param.getType()));
		} catch (Exception e) {
			
		}
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(Account acct) {
		if (acct == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		try {
			result.put("__jsonclass__", "Account");
			result.put("id", encode(acct.getId()));
			result.put("email", encode(acct.getEmail()));
			result.put("name", encode(acct.getName()));
			result.put("phoneNumber", encode(acct.getPhoneNumber()));
			result.put("lastContactTime", encode(acct.getLastContactTime()));
			result.put("emailToken", encode(acct.getEmailToken()));
			result.put("status", encode(acct.getStatus()));
			result.put("notification", encode(acct.getNotification()));
			result.put("description", encode(acct.getDescription()));
			result.put("applications", encode(acct.getApplications()));
			} catch (Exception e) {
				//  Skip it, send a partial object
			}
		return JSONObject.fromObject(result);
	}

	public static JSONObject encode(Application app) {
		if (app == null) return null;
		Map<String, Object> result = Maps.newHashMap();
		try {
			result.put("__jsonclass__", "Application");
			result.put("id", encode(app.getId()));
			result.put("name", encode(app.getName()));
			// Just send the ID so no recursion
			result.put("account", encode(app.getAccount().getId()));
			result.put("releaseDate", encode(app.getReleaseDate()));
			result.put("announcement", encode(app.getAnnouncement()));
		} catch (Exception e) {
			// ignore
		}
		return JSONObject.fromObject(result);
	}

	/**
   	 * Catch all used for returning values from invoked methods.
   	 * Generic invoke always returns "object" type.
   	 * Method overloading doesn't work since it is this type.
   	 * Need to query its underlying type and cast to a specific type for encoding.
   	 */
	public static JSONObject encode(Object value) throws Exception {
		if (value == null) return null;
		JSONObject jResult = null;
		Class<?> clazz = value.getClass();
		if (iLoggrObject.class.isAssignableFrom(clazz)) {
			jResult = encode((iLoggrObject) value);
		} else if (List.class.isAssignableFrom(clazz)) {
			jResult = JSONRPC.encode((List<?>) value);
		} else if (Set.class.isAssignableFrom(clazz)) {
			jResult = JSONRPC.encode((Set<?>) value);
		} else if (Boolean.class.isAssignableFrom(clazz)){
			jResult = JSONRPC.encode((Boolean)value);
		} else if (String.class.isAssignableFrom(clazz)){
			jResult = JSONRPC.encodeStringObject((String)value);
		} else if (Long.class.isAssignableFrom(clazz)){
			jResult = JSONRPC.encode((Long)value);
		} else if (Date.class.isAssignableFrom(clazz)){
			jResult = JSONRPC.encode((Date)value);
		} else if (Double.class.isAssignableFrom(clazz)){
			jResult = JSONRPC.encode((Double)value);
		} else if (Integer.class.isAssignableFrom(clazz)){
			jResult = JSONRPC.encode((Integer)value);
		} else {
			throw new Exception("Cannot encode result");
		}
		return jResult;
  	}

	public static JSONObject encode(Set<?> value) throws Exception {
		if (value == null) return null;
		JSONObject result = new JSONObject();
		result.put("__jsonclass__", "HashSet");
		JSONArray array = new JSONArray();

		// Could be a Set<Long> or Set<iLoggrObject>
		// TODO(jsirois): nvestigate if iLoggrObject is really needed
		for (Object item : value) {
			array.add(encode(item));
		}

		result.put("value", array);
		return result;
	}

	public static JSONObject encode(List<?> value) throws Exception {
		if (value == null) return null;
		JSONObject result = new JSONObject();
		result.put("__jsonclass__", "List");
		JSONArray array = new JSONArray();
		for (Object item : value) {
			array.add(encode(item));
		}
		result.put("value", array);
		return result;
	}

	public static JSONObject encode(iLoggrObject object) throws Exception {
		if (object == null) return null;
		// type check - TODO: must be better way to do this
		if (object.getClass().equals(Carrier.class)) {
			return encode((Carrier)object);
		} else if (object.getClass().equals(Event.class)) {
			return encode((Event)object);
		} else if (object.getClass().equals(Application.class)) {
			return encode((Application)object);
		} else if (object.getClass().equals(Account.class)) {
			return encode((Account)object);
		} else if (object.getClass().equals(Event.class)) {
			return encode((Event)object);
		} else if (object.getClass().equals(Phone.class)) {
			return encode((Provisioning)object);
		} else if (object.getClass().equals(Provisioning.class)) {
			return encode((Provisioning)object);
		} else if (object.getClass().equals(ProvisioningParameter.class)) {
			return encode((ProvisioningParameter)object);
		} else {
			throw new Exception("Cannot JSON encode unrecognized iLoggrObject");
		}

	}

	public static JSONObject encode(Date date) {
		if (date == null) return null;
		JSONObject result = new JSONObject();
		result.put("__jsonclass__", "Date");
		result.put("value", getDateFormat().format(date));
		return result;
	}

	public static SimpleDateFormat getDateFormat() {
		return new SimpleDateFormat("yyyyMMddHHmmss");
	}
}
