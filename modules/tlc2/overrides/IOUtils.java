/*******************************************************************************
 * Copyright (c) 2020 Microsoft Research. All rights reserved.
 *
 * The MIT License (MIT)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Contributors:
 *   Markus Alexander Kuppe - initial API and implementation
 ******************************************************************************/
package tlc2.overrides;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import tlc2.output.EC;
import tlc2.serializers.SerializerService;
import tlc2.tool.EvalException;
import tlc2.value.IValue;
import tlc2.value.ValueInputStream;
import tlc2.value.ValueOutputStream;
import tlc2.value.Values;
import tlc2.value.impl.BoolValue;
import tlc2.value.impl.IntValue;
import tlc2.value.impl.RecordValue;
import tlc2.value.impl.StringValue;
import tlc2.value.impl.TupleValue;
import tlc2.value.impl.Value;
import util.UniqueString;

public class IOUtils {

	@TLAPlusOperator(identifier = "IODeserialize", module = "IOUtils", warn = false)
	public static final IValue ioDeserialize(final StringValue absolutePath, final BoolValue compress)
			throws IOException {
		final ValueInputStream vis = new ValueInputStream(new File(absolutePath.val.toString()), compress.val);
		try {
			return vis.read(UniqueString.internTbl.toMap());
		} finally {
			vis.close();
		}
	}

	@TLAPlusOperator(identifier = "IOSerialize", module = "IOUtils", warn = false)
	public static final IValue ioSerialize(final IValue value, final StringValue absolutePath, final BoolValue compress)
			throws IOException {
		final ValueOutputStream vos = new ValueOutputStream(new File(absolutePath.val.toString()), compress.val);
		try {
			value.write(vos);
		} finally {
			vos.close();
		}
		return BoolValue.ValTrue;
	}

	@TLAPlusOperator(identifier = "Serialize", module = "IOUtils", warn = false)
	public static final Value serialize(final Value value, final StringValue absolutePath, final StringValue serializer, RecordValue options)
			throws IOException {

		final String serializerStr = serializer.getVal().toString();
		ServiceLoader<SerializerService> loader = ServiceLoader.load(SerializerService.class);

		Boolean fail = true;
		for(SerializerService serializerService : loader) {

			if(serializerService.getType().equals(serializerStr)) {
				serializerService.serialize(value, absolutePath, options);
				fail = false;
				break;
			}

		}

		if(fail) return BoolValue.ValFalse;
		return BoolValue.ValTrue;
	}

	@TLAPlusOperator(identifier = "Deserialize", module = "IOUtils", warn = false)
	public static final Value deserialize(final StringValue absolutePath, final StringValue serializer, final RecordValue options)
			throws IOException {

		final String serializerStr = serializer.getVal().toString();
		ServiceLoader<SerializerService> loader = ServiceLoader.load(SerializerService.class);

		for(SerializerService serializerService : loader) {
			if(serializerService.getType().equals(serializerStr)) {
				return serializerService.deserialize(absolutePath, options);
			}
		}

		return BoolValue.ValFalse;
	}

	static {
		// Eagerly lookup the environment, which is not going to change while the Java
		// process executes.
		final Map<String, String> env = System.getenv();

		final UniqueString[] names = new UniqueString[env.size()];
		final StringValue[] values = new StringValue[env.size()];

		final List<Map.Entry<String, String>> entries = new ArrayList<>(env.entrySet());
		for (int i = 0; i < entries.size(); i++) {
			names[i] = UniqueString.of(entries.get(i).getKey());
			values[i] = new StringValue(entries.get(i).getValue());
		}

		ENV = new RecordValue(names, values, false).normalize();
	}

	@TLAPlusOperator(identifier = "atoi", module = "IOUtils", minLevel = 0, warn = false)
	public static Value atoi(final Value v) {
		if (v instanceof StringValue) {
			final StringValue sv = (StringValue) v;
			try {
				final int i = Integer.parseInt(sv.val.toString());
				return IntValue.gen(i);
			} catch (Exception e) {
				// "fall-through" to eval exception below.
			}
		}
		throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
				new String[] { "atoi", "string", Values.ppr(v.toString()) });
	}
	
	private static final Value ENV;

	// The legal syntax for names/keys of environment variables appears undefined.
	// On Unix, the names are commonly upper-case characters and underscore. On
	// Windows, there are names that contain parentheses.  For those names that are
	// no legal record keys in TLA+, a user cannot use the  Record.key()  syntax but
	// has to revert to  Record["key()"]  .
	// IOEnv doesn't take the name/key as an argument to lookup individual
	// environment variables because we don't want to deal with unset names.
	@TLAPlusOperator(identifier = "IOEnv", module = "IOUtils", minLevel = 0, warn = false)
	public static Value ioEnv() throws IOException, InterruptedException {
		return ENV;
	}
	
	@TLAPlusOperator(identifier = "IOExec", module = "IOUtils", minLevel = 1, warn = false)
	public static Value ioExec(final Value parameter) throws IOException, InterruptedException {
		// 1. Check parameters and covert.
		if (!(parameter instanceof TupleValue)) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "IOExec", "sequence", Values.ppr(parameter.toString()) });
		}
		final TupleValue tv = (TupleValue) parameter;

		// 2. Build actual command by converting each parameter element to a string.
		//	No escaping or quoting is done so the process receives the exact string.
		final String[] command = Arrays.asList(tv.getElems()).stream()
				.map(IOUtils::convert)
				.toArray(size -> new String[size]);

		return runProcess(command);
	}

	@TLAPlusOperator(identifier = "IOEnvExec", module = "IOUtils", minLevel = 1, warn = false)
	public static Value ioEnvExec(final Value env, final Value parameter) throws IOException, InterruptedException {
		// Check env and parameters and covert.
		final RecordValue environment = (RecordValue) env.toRcd();
		if (environment == null) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "IOExecVars", "record", Values.ppr(env.toString()) });
		}
		if (!(parameter instanceof TupleValue)) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "IOExecVars", "sequence", Values.ppr(parameter.toString()) });
		}
		final TupleValue tv = (TupleValue) parameter;

		// Build actual command by converting each parameter element to a string.
		// No escaping or quoting is done so the process receives the exact string.
		final String[] command = Arrays.asList(tv.getElems()).stream()
				.map(IOUtils::convert)
				.toArray(size -> new String[size]);

		return runProcess(getEnv(environment), command);
	}

	@TLAPlusOperator(identifier = "IOExecTemplate", module = "IOUtils", minLevel = 1, warn = false)
	public static Value ioExecTemplate(final Value commandTemplate, final Value parameter) throws IOException, InterruptedException {
		// 1. Check parameters and covert.
		if (!(commandTemplate instanceof TupleValue)) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "IOExec", "sequence", Values.ppr(commandTemplate.toString()) });
		}
		if (!(parameter instanceof TupleValue)) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "IOExec", "sequence", Values.ppr(parameter.toString()) });
		}
		final TupleValue sv = (TupleValue) commandTemplate;
		final TupleValue tv = (TupleValue) parameter;

		// 2. Build actual command-line by merging command and parameter.
		final String[] command = Arrays.asList(sv.getElems()).stream().map(IOUtils::convert)
				.toArray(size -> new String[size]);
		final Object[] params = Arrays.asList(tv.getElems()).stream().map(IOUtils::convert)
				.toArray(size -> new Object[size]);
		for (int i = 0; i < command.length; ++i) {
			command[i] = String.format(command[i], params);
		}

		return runProcess(command);
	}
	
	@TLAPlusOperator(identifier = "IOEnvExecTemplate", module = "IOUtils", minLevel = 1, warn = false)
	public static Value ioEnvExecTemplate(final Value env, final Value commandTemplate, final Value parameter) throws IOException, InterruptedException {
		// Check env and parameters and covert.
		final RecordValue environment = (RecordValue) env.toRcd();
		if (environment == null) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "ioEnvExecTemplate", "record", Values.ppr(env.toString()) });
		}
		// 1. Check parameters and covert.
		if (!(commandTemplate instanceof TupleValue)) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "ioEnvExecTemplate", "sequence", Values.ppr(commandTemplate.toString()) });
		}
		if (!(parameter instanceof TupleValue)) {
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "ioEnvExecTemplate", "sequence", Values.ppr(parameter.toString()) });
		}
		final TupleValue sv = (TupleValue) commandTemplate;
		final TupleValue tv = (TupleValue) parameter;

		// 2. Build actual command-line by merging command and parameter.
		final String[] command = Arrays.asList(sv.getElems()).stream().map(IOUtils::convert)
				.toArray(size -> new String[size]);
		final Object[] params = Arrays.asList(tv.getElems()).stream().map(IOUtils::convert)
				.toArray(size -> new Object[size]);
		for (int i = 0; i < command.length; ++i) {
			command[i] = String.format(command[i], params);
		}

		return runProcess(getEnv(environment), command);
	}

	private static Map<String, String> getEnv(final RecordValue environment) {
		// Convert record of environment variables to what ProcessBuilder works with.
		final Map<String, String> penv = new HashMap<>();
		for (int i = 0; i < environment.size(); i++) {
			final UniqueString name = environment.names[i];
			final Value value = environment.values[i];
			penv.put(name.toString(), value.toUnquotedString());
		}
		return penv;
	}

	private static Value runProcess(final String[] command) throws IOException, InterruptedException {
		return runProcess(new ProcessBuilder(command));
	}

	private static Value runProcess(final Map<String, String> env, final String[] command)
			throws IOException, InterruptedException {
		final ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.environment().putAll(env);
		return runProcess(processBuilder);
	}
	
	private static Value runProcess(final ProcessBuilder processBuilder)
			throws IOException, InterruptedException {
		// 3. Run command-line and receive its output.
		final Process process = processBuilder/* .inheritIO() */.start();

		final StringValue stdout = new StringValue(stringFromInputStream(process.getInputStream()));
		final StringValue stderr = new StringValue(stringFromInputStream(process.getErrorStream()));
		final IntValue exitCode = IntValue.gen(process.waitFor());

		return new RecordValue(EXEC_NAMES, new Value[] { exitCode, stdout, stderr }, false);
	}

        private static String stringFromInputStream(InputStream inputStream) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result.toString();
        }

	private static String convert(IValue v) {
		if (! (v instanceof StringValue)) {
			// XXX Proper exception
			throw new EvalException(EC.TLC_MODULE_ONE_ARGUMENT_ERROR,
					new String[] { "IOExec", "sequence", Values.ppr(v.toString()) });
		}
		final StringValue sv = (StringValue) v;

		return sv.val.toString();
	}

	private static final UniqueString EXITVALUE = UniqueString.uniqueStringOf("exitValue");
	private static final UniqueString STDOUT = UniqueString.uniqueStringOf("stdout");
	private static final UniqueString STDERR = UniqueString.uniqueStringOf("stderr");
	private static final UniqueString[] EXEC_NAMES = new UniqueString[] { EXITVALUE, STDOUT, STDERR };
}
