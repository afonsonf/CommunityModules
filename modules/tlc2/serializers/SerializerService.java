package tlc2.serializers;

import java.io.IOException;

import tlc2.value.impl.RecordValue;
import tlc2.value.impl.StringValue;
import tlc2.value.impl.Value;

public abstract class SerializerService {
	public abstract String getType();
	public abstract Value serialize(final Value value, final StringValue absolutePath, final RecordValue options) throws IOException;
	public abstract Value deserialize(final StringValue absolutePath, final RecordValue options) throws IOException;
}
