.. _component-howtos-application-development-daml-codegen-java:

Daml Codegen for Java
=====================

Use the Daml Codegen for Java (``daml codegen java``) to generate Java classes representing all Daml data types defined
in a Daml Archive (.dar) file.
These classes simplify constructing the types required by the Java gRPC bindings for the
:externalref:`gRPC Ledger API <ledger-api-services>`; for example, ``com.daml.ledger.api.v2.CreateCommand`` and ``com.daml.ledger.api.v2.ExerciseCommand``.
They also provide JSON decoding utilities, making it easier to work with JSON when using the
:externalref:`JSON Ledger API <json-api>`.

See :ref:`How to work with contracts and transactions in Java <howto-applications-work-with-contracts-java>` for details on how to use the generated classes. See the sections below for guidance on setting up and invoking the codegen.

Install
-------

Install the Daml Codegen for Java by :ref:`installing the Daml Assistant <daml-assistant-install>`.

Configure
---------

To configure the Daml Codegen, choose one of the two following methods:

- **Command line configuration**: Specify all settings directly in the command line.

- **Project file configuration**: Define all settings in the `daml.yaml` file.

Command line configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^

To view all available command line configuration options for Daml Codegen for Java, run ``daml codegen java --help`` in your terminal:

.. code-block:: none

      <DAR-file[=package-prefix]>...
                               DAR file to use as input of the codegen with an optional, but recommend, package prefix for the generated sources.
      -o, --output-directory <value>
                               Output directory for the generated sources
      -d, --decoderClass <value>
                               Fully Qualified Class Name of the optional Decoder utility
      -V, --verbosity <value>  Verbosity between 0 (only show errors) and 4 (show all messages) -- defaults to 0
      -r, --root <value>       Regular expression for fully-qualified names of templates to generate -- defaults to .*
      --help                   This help text

Project file configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^

Specify the above settings in the ``codegen`` element of the Daml project file ``daml.yaml``.

Here is an example::

    sdk-version: 3.3.0-snapshot.20250507.0
    name: quickstart
    source: daml
    init-script: Main:initialize
    parties:
      - Alice
      - Bob
      - USD_Bank
      - EUR_Bank
    version: 0.0.1
    exposed-modules:
      - Main
    dependencies:
      - daml-prim
      - daml-stdlib
    codegen:
      java:
        package-prefix: com.daml.quickstart.iou
        output-directory: java-codegen/src/main/java
        verbosity: 2

Operate
-------

Run the Daml Codegen using project file configuration with::

    $ daml codegen java

or using command line configuration with::

    $ daml codegen java ./.daml/dist/quickstart-0.0.1.dar=com.daml.quickstart.iou --output-directory=java-codegen/src/main/java --verbosity=2

References
----------

.. _component-howtos-application-development-daml-codegen-java-generated-code:

Generated Java code
^^^^^^^^^^^^^^^^^^^

.. _component-howtos-application-development-daml-codegen-java-primitive-types:

Daml primitives to Java types
"""""""""""""""""""""""""""""

Daml built-in types are translated to the following equivalent types in Java:

+--------------------------------+--------------------------------------------+------------------------+
| Daml type                      | Java type                                  | Java Bindings          |
|                                |                                            | Value type             |
+================================+============================================+========================+
| ``Int``                        | ``java.lang.Long``                         | `Int64`_               |
+--------------------------------+--------------------------------------------+------------------------+
| ``Numeric``                    | ``java.math.BigDecimal``                   | `Numeric`_             |
+--------------------------------+--------------------------------------------+------------------------+
| ``Text``                       | ``java.lang.String``                       | `Text`_                |
+--------------------------------+--------------------------------------------+------------------------+
| ``Bool``                       | ``java.util.Boolean``                      | `Bool`_                |
+--------------------------------+--------------------------------------------+------------------------+
| ``Party``                      | ``java.lang.String``                       | `Party`_               |
+--------------------------------+--------------------------------------------+------------------------+
| ``Date``                       | ``java.time.LocalDate``                    | `Date`_                |
+--------------------------------+--------------------------------------------+------------------------+
| ``Time``                       | ``java.time.Instant``                      | `Timestamp`_           |
+--------------------------------+--------------------------------------------+------------------------+
| ``List`` or ``[]``             | ``java.util.List``                         | `DamlList`_            |
+--------------------------------+--------------------------------------------+------------------------+
| ``TextMap``                    | ``java.util.Map``                          | `DamlTextMap`_         |
|                                | Restricted to using ``String`` keys.       |                        |
+--------------------------------+--------------------------------------------+------------------------+
| ``Optional``                   | ``java.util.Optional``                     | `DamlOptional`_        |
+--------------------------------+--------------------------------------------+------------------------+
| ``()`` (Unit)                  | **None** since the Java language does not  | `Unit`_                |
|                                | have a direct equivalent of Daml’s Unit    |                        |
|                                | type ``()``, the generated code uses the   |                        |
|                                | Java Bindings value type.                  |                        |
+--------------------------------+--------------------------------------------+------------------------+
| ``ContractId``                 | Fields of type ``ContractId X`` refer to   | `ContractId`_          |
|                                | the generated ``ContractId`` class of the  |                        |
|                                | respective template ``X``.                 |                        |
+--------------------------------+--------------------------------------------+------------------------+


Escaping rules
""""""""""""""

To avoid clashes with Java keywords, the Daml Codegen applies escaping rules to the following Daml identifiers:

* Type names (except the already mapped :ref:`built-in types <component-howtos-application-development-daml-codegen-java-primitive-types>`)
* Constructor names
* Type parameters
* Module names
* Field names

If any of these identifiers match one of the `Java reserved keywords <https://docs.oracle.com/javase/specs/jls/se12/html/jls-3.html#jls-3.9>`__, the Daml Codegen appends a dollar sign ``$`` to the name. For example, a field with the name ``import`` will be generated as a Java field with the name ``import$``.

Generated classes
"""""""""""""""""

Every user-defined data type in Daml (template, record, and variant) is represented by one or more Java classes as described in this section.

The Java package for the generated classes is the equivalent of the lowercase Daml module name.

.. code-block:: daml
  :caption: Daml

  module Foo.Bar.Baz where

.. code-block:: java
  :caption: Java

  package foo.bar.baz;

Records (a.k.a. product types)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A :ref:`Daml record <daml-ref-record-types>` is represented by a Java class with fields that have the same name as the Daml record fields. A Daml field having the type of another record is represented as a field having the type of the generated class for that record.

.. literalinclude:: ./code-snippets/Com/Acme/ProductTypes.daml
   :language: daml
   :start-after: -- start snippet: product types example
   :end-before: -- end snippet: product types example
   :caption: Com/Acme/ProductTypes.daml

A Java file that defines the class for the type ``Person`` is generated:

.. code-block:: java
  :caption: com/acme/producttypes/Person.java

  package com.acme.producttypes;

  public class Person extends DamlRecord<Person> {
    public final Name name;
    public final BigDecimal age;

    public static Person fromValue(Value value$) { /* ... */ }

    public Person(Name name, BigDecimal age) { /* ... */ }
    public DamlRecord toValue() { /* ... */ }
  }

A Java file that defines the class for the type ``Name`` is generated:

.. code-block:: java
  :caption: com/acme/producttypes/Name.java

  package com.acme.producttypes;

  public class Name extends DamlRecord<Name> {
    public final String firstName;
    public final String lastName;

    public static Person fromValue(Value value$) { /* ... */ }

    public Name(String firstName, String lastName) { /* ... */ }
    public DamlRecord toValue() { /* ... */ }
  }

.. _component-howtos-application-development-daml-codegen-java-templates:

Templates
~~~~~~~~~

The Daml Codegen generates the following classes for a Daml template:

  **TemplateName**
      Represents the contract data or the template fields.

  **TemplateName.ContractId**
      Used whenever a contract ID of the corresponding template is used in another template or record, for example: ``data Foo = Foo (ContractId Bar)``. This class also provides methods to generate an ``ExerciseCommand`` for each choice that can be sent to the ledger with the Java Bindings.

  **TemplateName.Contract**
      Represents an actual contract on the ledger. It contains a field for the contract ID (of type ``TemplateName.ContractId``)
      and a field for the template data (of type ``TemplateName``). With the static method
      ``TemplateName.Contract.fromCreatedEvent``, you can deserialize a `CreatedEvent </javadocs/3.3/com/daml/ledger/javaapi/data/CreatedEvent.html>`_
      to an instance of ``TemplateName.Contract``.


  .. literalinclude:: ./code-snippets/Com/Acme/Templates.daml
     :language: daml
     :start-after: -- start snippet: template example
     :end-before: -- end snippet: template example
     :caption: Com/Acme/Templates.daml

In particular, the codegen generates a file that defines six Java classes and one interface:

#. ``Bar``
#. ``Bar.ContractId``
#. ``Bar.Contract``
#. ``Bar.CreateAnd``
#. ``Bar.JsonDecoder$``
#. ``Bar.ByKey``
#. ``Bar.Exercises``

.. code-block:: java
  :caption: com/acme/templates/Bar.java
  :emphasize-lines: 3,21,27,35,43,47

  package com.acme.templates;

  public class Bar extends Template {

    public static final Identifier TEMPLATE_ID = new Identifier("some-package-id", "Com.Acme.Templates", "Bar");

    public static final Choice<Bar, Archive, Unit> CHOICE_Archive =
      Choice.create(/* ... */);

    public static final ContractCompanion.WithKey<Contract, ContractId, Bar, BarKey> COMPANION =
        new ContractCompanion.WithKey<>("com.acme.templates.Bar",
          TEMPLATE_ID, ContractId::new, Bar::fromValue, Contract::new, e -> BarKey.fromValue(e), List.of(CHOICE_Archive));

    public final String owner;
    public final String name;

    public CreateAnd createAnd() { /* ... */ }

    public static ByKey byKey(BarKey key) { /* ... */ }

    public static class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<Bar>
        implements Exercises<ExerciseCommand> {
      // inherited:
      public final String contractId;
    }

    public interface Exercises<Cmd> extends com.daml.ledger.javaapi.data.codegen.Exercises<Cmd> {
      default Cmd exerciseArchive(Unit arg) { /* ... */ }

      default Cmd exerciseBar_SomeChoice(Bar_SomeChoice arg) { /* ... */ }

      default Cmd exerciseBar_SomeChoice(String aName) { /* ... */ }
    }

    public static class Contract extends ContractWithKey<ContractId, Bar, BarKey> {
      // inherited:
      public final ContractId id;
      public final Bar data;

      public static Contract fromCreatedEvent(CreatedEvent event) { /* ... */ }
    }

    public static final class CreateAnd
        extends com.daml.ledger.javaapi.data.codegen.CreateAnd
        implements Exercises<CreateAndExerciseCommand> { /* ... */ }

    public static class JsonDecoder$ { /* ... */ }

    public static final class ByKey
        extends com.daml.ledger.javaapi.data.codegen.ByKey
        implements Exercises<ExerciseByKeyCommand> { /* ... */ }
  }

Note that ``byKey`` and ``ByKey`` will only be generated for templates that define a key.

Variants (a.k.a. sum types)
~~~~~~~~~~~~~~~~~~~~~~~~~~~

A :ref:`variant or sum type <daml-ref-sum-types>` is a type with multiple constructors, where each constructor wraps a value of another type. The generated code is comprised of an abstract class for the variant type itself and a subclass thereof for each constructor. Classes for variant constructors are similar to classes for records.

.. literalinclude:: ./code-snippets/Com/Acme/Variants.daml
   :language: daml
   :start-after: -- start snippet: variant example
   :end-before: -- end snippet: variant example
   :caption: Com/Acme/Variants.daml

The Java code generated for this variant is:

.. code-block:: java
  :caption: com/acme/variants/BookAttribute.java

  package com.acme.variants;

  public class BookAttribute extends Variant<BookAttribute> {
    public static BookAttribute fromValue(Value value) { /* ... */ }

    public static BookAttribute fromValue(Value value) { /* ... */ }
    public abstract Variant toValue();
  }

.. code-block:: java
  :caption: com/acme/variants/bookattribute/Pages.java

  package com.acme.variants.bookattribute;

  public class Pages extends BookAttribute {
    public final Long longValue;

    public static Pages fromValue(Value value) { /* ... */ }

    public Pages(Long longValue) { /* ... */ }
    public Variant toValue() { /* ... */ }
  }

.. code-block:: java
  :caption: com/acme/variants/bookattribute/Authors.java

  package com.acme.variants.bookattribute;

  public class Authors extends BookAttribute {
    public final List<String> listValue;

    public static Authors fromValue(Value value) { /* ... */ }

    public Author(List<String> listValue) { /* ... */ }
    public Variant toValue() { /* ... */ }

  }

.. code-block:: java
  :caption: com/acme/variants/bookattribute/Title.java

  package com.acme.variants.bookattribute;

  public class Title extends BookAttribute {
    public final String stringValue;

    public static Title fromValue(Value value) { /* ... */ }

    public Title(String stringValue) { /* ... */ }
    public Variant toValue() { /* ... */ }
  }

.. code-block:: java
  :caption: com/acme/variants/bookattribute/Published.java

  package com.acme.variants.bookattribute;

  public class Published extends BookAttribute {
    public final Long year;
    public final String publisher;

    public static Published fromValue(Value value) { /* ... */ }

    public Published(Long year, String publisher) { /* ... */ }
    public Variant toValue() { /* ... */ }
  }

Enums
~~~~~

An enum type is a simplified :ref:`sum type <daml-ref-sum-types>` with multiple
constructors but without argument nor type parameters. The generated code is
standard java Enum whose constants map enum type constructors.


.. literalinclude:: ./code-snippets/Com/Acme/Enum.daml
   :language: daml
   :start-after: -- start snippet: enum example
   :end-before: -- end snippet: enum example
   :caption: Com/Acme/Enum.daml

The Java code generated for this variant is:

.. code-block:: java
  :caption: com/acme/enum/Color.java

  package com.acme.enum;

  public enum Color implements DamlEnum<Color> {
    RED,
    GREEN,
    BLUE;

    /* ... */
    public static final Color fromValue(Value value$) { /* ... */ }
    public final DamlEnum toValue() { /* ... */ }
  }

Parameterized types
~~~~~~~~~~~~~~~~~~~

.. note::

   This section is only included for completeness. The ``fromValue`` and ``toValue`` methods would typically come from a template that does not have any unbound type parameters.

The Daml Codegen uses Java Generic types to represent :ref:`Daml parameterized types <daml-ref-parameterized-types>`.

This Daml fragment defines the parameterized type ``Attribute``, used by the ``BookAttribute`` type for modeling the characteristics of the book:

.. literalinclude:: ./code-snippets/Com/Acme/ParameterizedTypes.daml
   :language: daml
   :start-after: -- start snippet: parameterized types example
   :end-before: -- end snippet: parameterized types example
   :caption: Com/Acme/ParameterizedTypes.daml

The Daml Codegen generates a Java file with a generic class for  the ``Attribute a`` data type:

.. code-block:: java
  :caption: com/acme/parameterizedtypes/Attribute.java
  :emphasize-lines: 3,8,10

  package com.acme.parameterizedtypes;

  public class Attribute<a> {
    public final a value;

    public Attribute(a value) { /* ... */  }

    public DamlRecord toValue(Function<a, Value> toValuea) { /* ... */ }

    public static <a> Attribute<a> fromValue(Value value$, Function<Value, a> fromValuea) { /* ... */ }
  }


Convert a value of a generated type to a Java Bindings Value
............................................................

To convert an instance of the generic type ``Attribute<a>`` to a Java Bindings `Value`_, call the ``toValue`` method and pass a function as the ``toValuea`` argument for converting the field of type ``a`` to the respective Java Bindings `Value`_. The name of the parameter consists of ``toValue`` and the name of the type parameter, in this case ``a``, to form the name ``toValuea``.

Below is a Java fragment that converts an attribute with a ``java.lang.Long`` value to the Java Bindings representation using the *method reference* ``Int64::new``.

.. code-block:: java

  Attribute<Long> pagesAttribute = new Attributes<>(42L);

  Value serializedPages = pagesAttribute.toValue(Int64::new);

See :ref:`Daml To Java Type Mapping <component-howtos-application-development-daml-codegen-java-primitive-types>` for an overview of the Java Bindings `Value`_ types.

.. note::

    If the Daml type is a record or variant with more than one type parameter, you need to pass a conversion function to the ``toValue`` method for each type parameter.

Create a Value of a generated type from a Java Bindings Value
.............................................................

Analogous to the ``toValue`` method, to create a value of a generated type, call the method ``fromValue`` and pass conversion functions from a Java Bindings `Value`_ type to the expected Java type.

.. code-block:: java

  Attribute<Long> pagesAttribute = Attribute.<Long>fromValue(serializedPages,
      f -> f.asInt64().getOrElseThrow(() -> throw new IllegalArgumentException("Expected Int field").getValue());

See Java Bindings `Value`_ class for the methods to transform the Java Bindings types into corresponding Java types.


Non-exposed parameterized types
...............................

If the parameterized type is contained in a type where the *actual* type is specified (as in the ``BookAttributes`` type above), then the conversion methods of the enclosing type provides the required conversion function parameters automatically.


Convert optional values
.......................

The conversion of the Java ``Optional`` requires two steps. The
``Optional`` must be mapped in order to convert its contains before
to be passed to ``DamlOptional::of`` function.

.. code-block:: java

  Attribute<Optional<Long>> idAttribute = new Attribute<List<Long>>(Optional.of(42));

  val serializedId = DamlOptional.of(idAttribute.map(Int64::new));

To convert back `DamlOptional`_ to Java ``Optional``, one must use the
containers method ``toOptional``. This method expects a function to
convert back the value possibly contains in the container.

.. code-block:: java

  Attribute<Optional<Long>> idAttribute2 =
    serializedId.toOptional(v -> v.asInt64().orElseThrow(() -> new IllegalArgumentException("Expected Int64 element")));

Convert collection values
.........................

`DamlCollectors`_ provides collectors to converted Java collection
containers such as ``List`` and ``Map`` to DamlValues in one pass. The
builders for those collectors require functions to convert the element
of the container.

.. code-block:: java

  Attribute<List<String>> authorsAttribute =
      new Attribute<List<String>>(Arrays.asList("Homer", "Ovid", "Vergil"));

  Value serializedAuthors =
      authorsAttribute.toValue(f -> f.stream().collect(DamlCollector.toList(Text::new));

To convert back Daml containers to Java ones, one must use the
containers methods ``toList`` or ``toMap``. Those methods expect
functions to convert back the container's entries.

.. code-block:: java

  Attribute<List<String>> authorsAttribute2 =
      Attribute.<List<String>>fromValue(
          serializedAuthors,
          f0 -> f0.asList().orElseThrow(() -> new IllegalArgumentException("Expected DamlList field"))
               .toList(
                   f1 -> f1.asText().orElseThrow(() -> new IllegalArgumentException("Expected Text element"))
                        .getValue()
               )
      );


Daml interfaces
~~~~~~~~~~~~~~~

From this Daml definition:


.. literalinclude:: ./code-snippets/Interfaces.daml
   :language: daml
   :start-after: -- start snippet: interface example
   :end-before: -- end snippet: interface example
   :caption: Interfaces.daml

The generated file for the interface definition can be seen below.
Effectively it is a class that contains only the inner type ContractId because one will always only be able to deal with Interfaces via their ContractId.

.. code-block:: java
  :caption: interfaces/TIf.java

  package interfaces

  /* imports */

  public final class TIf {
    public static final Identifier TEMPLATE_ID = new Identifier("94fb4fa48cef1ec7d474ff3d6883a00b2f337666c302ec5e2b87e986da5c27a3", "Interfaces", "TIf");

    public static final Choice<TIf, Transfer, ContractId> CHOICE_Transfer =
      Choice.create(/* ... */);

    public static final Choice<TIf, Archive, Unit> CHOICE_Archive =
      Choice.create(/* ... */);

    public static final INTERFACE INTERFACE = new INTERFACE();

    public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<TIf>
        implements Exercises<ExerciseCommand> {
      public ContractId(String contractId) { /* ... */ }
    }

    public interface Exercises<Cmd> extends com.daml.ledger.javaapi.data.codegen.Exercises<Cmd> {
      default Cmd exerciseUseless(Useless arg) { /* ... */ }

      default Cmd exerciseHam(Ham arg) { /* ... */ }
    }

    public static final class CreateAnd
        extends com.daml.ledger.javaapi.data.codegen.CreateAnd.ToInterface
        implements Exercises<CreateAndExerciseCommand> { /* ... */ }

    public static final class ByKey
        extends com.daml.ledger.javaapi.data.codegen.ByKey.ToInterface
        implements Exercises<ExerciseByKeyCommand> { /* ... */ }

    public static final class INTERFACE extends InterfaceCompanion<TIf> { /* ... */}
  }

For templates the code generation will be slightly different if a template implements interfaces.
To allow converting the ContractId of a template to an interface ContractId, an additional conversion method called `toInterface` is generated.
An ``unsafeFromInterface`` is also generated to make the unchecked conversion in the other direction.

.. code-block:: java
  :caption: interfaces/Child.java

  package interfaces

  /* ... */

  public final class Child extends Template {

    /* ... */

    public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<Child>
        implements Exercises<ExerciseCommand> {

      /* ... */

      public TIf.ContractId toInterface(TIf.INTERFACE interfaceCompanion) { /* ... */ }

      public static ContractId unsafeFromInterface(TIf.ContractId interfaceContractId) { /* ... */ }

    }

    public interface Exercises<Cmd> extends com.daml.ledger.javaapi.data.codegen.Exercises<Cmd> {
      default Cmd exerciseBar(Bar arg) { /* ... */ }

      default Cmd exerciseBar() { /* ... */ }
    }

    /* ... */

  }


.. _Value: //javadocs/com/daml/ledger/javaapi/data/Value.html
.. _Unit: //javadocs/com/daml/ledger/javaapi/data/Unit.html
.. _Bool: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Bool.html
.. _Int64: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Int64.html
.. _Decimal: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Decimal.html
.. _Numeric: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Numeric.html
.. _Date: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Date.html
.. _Timestamp: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Timestamp.html
.. _Text: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Text.html
.. _Party: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/Party.html
.. _ContractId: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/ContractId.html
.. _DamlOptional: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/DamlOptional.html
.. _DamlList: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/DamlList.html
.. _DamlTextMap: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/DamlTextMap.html
.. _DamlMap: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/DamlMap.html
.. _DamlCollectors: /app-dev/bindings-java/javadocs/com/daml/ledger/javaapi/data/DamlCollectors.html
