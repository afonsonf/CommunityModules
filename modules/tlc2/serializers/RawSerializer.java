package tlc2.serializers;

import java.io.File;
import java.io.IOException;

import tlc2.value.ValueInputStream;
import tlc2.value.ValueOutputStream;
import tlc2.value.impl.BoolValue;
import tlc2.value.impl.RecordValue;
import tlc2.value.impl.StringValue;
import tlc2.value.impl.Value;
import util.UniqueString;

public class RawSerializer extends SerializerService {

	@Override
	public String getType() { return "RAW"; }
	
	@Override
	public Value serialize(final Value value, final StringValue absolutePath, final RecordValue options) throws IOException{
		final BoolValue compress = (BoolValue) options.select(new StringValue("compress"));
		final ValueOutputStream vos = new ValueOutputStream(new File(absolutePath.val.toString()), compress.val);

		try {
			value.write(vos);
		} finally {
			vos.close();
		}
		
		return BoolValue.ValTrue;
	}

	@Override
	public Value deserialize(final StringValue absolutePath, final RecordValue options) throws IOException{
		final BoolValue compress = (BoolValue) options.select(new StringValue("compress"));
		final ValueInputStream vis = new ValueInputStream(new File(absolutePath.val.toString()), compress.val);

		try {
			return (Value) vis.read(UniqueString.internTbl.toMap());
		} finally {
			vis.close();
		}
	}

}
