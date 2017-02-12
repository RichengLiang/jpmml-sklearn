/*
 * Copyright (c) 2015 Villu Ruusmann
 *
 * This file is part of JPMML-SkLearn
 *
 * JPMML-SkLearn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SkLearn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SkLearn.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.sklearn;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Set;

import joblib.NDArrayWrapperConstructor;
import joblib.NumpyArrayWrapper;
import net.razorvine.pickle.Opcodes;
import net.razorvine.pickle.Unpickler;
import net.razorvine.pickle.objects.ClassDict;
import numpy.core.NDArray;

public class PickleUtil {

	private PickleUtil(){
	}

	static
	public void init() throws Exception {
		Thread thread = Thread.currentThread();

		ClassLoader classLoader = thread.getContextClassLoader();
		if(classLoader == null){
			classLoader = ClassLoader.getSystemClassLoader();
		}

		Enumeration<URL> urls = classLoader.getResources("META-INF/sklearn2pmml.properties");
		while(urls.hasMoreElements()){
			URL url = urls.nextElement();

			try(InputStream is = url.openStream()){
				Properties properties = new Properties();
				properties.load(is);

				init(classLoader, properties);
			}
		}
	}

	static
	public void init(ClassLoader classLoader, Properties properties) throws ClassNotFoundException {

		if(properties.isEmpty()){
			return;
		}

		Set<String> keys = properties.stringPropertyNames();
		for(String key : keys){
			String value = properties.getProperty(key);

			int dot = key.lastIndexOf('.');
			if(dot < 0){
				throw new IllegalArgumentException(key);
			}

			String module = key.substring(0, dot);
			String name = key.substring(dot + 1);

			Class<?> clazz = classLoader.loadClass(value);

			ObjectConstructor constructor;

			if((CClassDict.class).isAssignableFrom(clazz)){
				constructor = new ExtensionObjectConstructor(module, name, (Class<? extends CClassDict>)clazz);
			} else

			if((ClassDict.class).isAssignableFrom(clazz)){
				constructor = new ObjectConstructor(module, name, (Class<? extends ClassDict>)clazz);
			} else

			{
				throw new IllegalArgumentException(value);
			}

			Unpickler.registerConstructor(constructor.getModule(), constructor.getName(), constructor);
		}
	}

	static
	public Storage createStorage(File file){

		try {
			InputStream is = new FileInputStream(file);

			try {
				return new CompressedInputStreamStorage(is);
			} catch(IOException ioe){
				is.close();
			}
		} catch(IOException ioe){
			// Ignored
		}

		return new FileStorage(file);
	}

	static
	public Object unpickle(Storage storage) throws IOException {
		ObjectConstructor[] constructors = {
			new NDArrayWrapperConstructor("joblib.numpy_pickle", "NDArrayWrapper", storage),
			new NDArrayWrapperConstructor("sklearn.externals.joblib.numpy_pickle", "NDArrayWrapper", storage),
		};

		for(ObjectConstructor constructor : constructors){
			Unpickler.registerConstructor(constructor.getModule(), constructor.getName(), constructor);
		}

		try(final InputStream is = storage.getObject()){
			Unpickler unpickler = new Unpickler(){

				@Override
				protected Object dispatch(short key) throws IOException {
					Object result = super.dispatch(key);;

					if(key == Opcodes.BUILD){
						Object head = super.stack.peek();

						// Modify the stack by replacing NumpyArrayWrapper with NDArray
						if(head instanceof NumpyArrayWrapper){
							NumpyArrayWrapper arrayWrapper = (NumpyArrayWrapper)head;

							super.stack.pop();

							NDArray array = arrayWrapper.toArray(is);

							super.stack.add(array);
						}
					}

					return result;
				}
			};

			return unpickler.load(is);
		}
	}
}