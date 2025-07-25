.. _component-howtos-application-development-daml-codegen-javascript:

Daml Codegen for JavaScript
===========================

Use the Daml Codegen for JavaScript (``daml codegen js``) to generate JavaScript/TypeScript code representing all Daml data types
defined in a Daml Archive (.dar) file.


The generated code makes it easier to construct types and work with JSON when using the
:externalref:`JSON Ledger API <json-api>`.

See :externalref:`Get started with Canton and the JSON Ledger API <tutorial-canton-and-the-json-ledger-api>` for details on how to use the generated code to
interact with JSON Ledger API. See the sections below for guidance on setting up and invoking the codegen.

Install
-------

Install the Daml Codegen for JavaScript by :ref:`installing the Daml Assistant <daml-assistant-install>`.

Configure
---------

To configure the Daml Codegen, choose one of the two following methods:

- **Command line configuration**: Specify all settings directly in the command line.

- **Project file configuration**: Define all settings in the `daml.yaml` file.

Command line configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^

To view all available command line configuration options for Daml Codegen for JavaScript, run ``daml codegen js --help`` in your terminal:

.. code-block:: none

      DAR-FILES                DAR files to generate TypeScript bindings for
      -o DIR                   Output directory for the generated packages
      -s SCOPE                 The NPM scope name for the generated packages;
                               defaults to daml.js
      -h,--help                Show this help text



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
      js:
        output-directory: ui/daml.js
        npm-scope: daml.js

Operate
-------

Run the Daml Codegen using project file configuration with::

    $ daml codegen js

or using command line configuration with::

    $ daml codegen js ./.daml/dist/quickstart-0.0.1.dar -o ui/daml.js -s daml.js

References
----------

.. _component-howtos-application-development-daml-codegen-javascript-generated-code:

Generated JavaScript/TypeScript code
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. _component-howtos-application-development-daml-codegen-javascript-primitive-types:

Daml primitives to TypeScript
"""""""""""""""""""""""""""""

Daml built-in types are translated to the following equivalent types in TypeScript.
The TypeScript equivalents of the primitive Daml types are provided by the
`@daml/types </typedocs/3.3/daml-types/index.html>`_.

**Interfaces**:

- ``interface Template<T extends object, K = unknown, I extends string = string>``
- ``interface Choice<T extends object, C, R, K = unknown>``

**Types**:

+-------------------+--------------------+----------------------------------+
| Daml              | TypeScript         | TypeScript definition            |
+===================+====================+==================================+
| ``()``            | ``Unit``           | ``{}``                           |
+-------------------+--------------------+----------------------------------+
| ``Bool``          | ``Bool``           | ``boolean``                      |
+-------------------+--------------------+----------------------------------+
| ``Int``           | ``Int``            | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``Decimal``       | ``Decimal``        | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``Numeric ν``     | ``Numeric``        | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``Text``          | ``Text``           | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``Time``          | ``Time``           | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``Party``         | ``Party``          | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``[τ]``           | ``List<τ>``        | ``τ[]``                          |
+-------------------+--------------------+----------------------------------+
| ``Date``          | ``Date``           | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``ContractId τ``  | ``ContractId<τ>``  | ``string``                       |
+-------------------+--------------------+----------------------------------+
| ``Optional τ``    | ``Optional<τ>``    | ``null | (null extends τ ?``     |
|                   |                    | ``[] | [Exclude<τ, null>] : τ)`` |
+-------------------+--------------------+----------------------------------+
| ``TextMap τ``     | ``TextMap<τ>``     | ``{ [key: string]: τ }``         |
+-------------------+--------------------+----------------------------------+
| ``(τ₁, τ₂)``      | ``Tuple₂<τ₁, τ₂>`` | ``{_1: τ₁; _2: τ₂}``             |
+-------------------+--------------------+----------------------------------+

.. note::
   The types given in the **TypeScript** column are defined in @daml/types.

.. note::
   For *n*-tuples where *n ≥ 3*, representation is analogous with the pair case (the last line of the table).

.. note::
   The TypeScript types ``Time``, ``Decimal``, ``Numeric`` and ``Int`` all alias to ``string``. These choices relate to
   the avoidance of precision loss under serialization over the :externalref:`JSON Ledger API <json-api>`.

.. note::
   The TypeScript definition of type ``Optional<τ>`` in the above table might look complicated. It accounts for differences in the encoding of optional values when nested versus when they are not (i.e. "top-level"). For example, ``null`` and ``"foo"`` are two possible values of ``Optional<Text>`` whereas, ``[]`` and ``["foo"]`` are two possible values of type ``Optional<Optional<Text>>`` (``null`` is another possible value, ``[null]`` is **not**).

Generated TypeScript mappings
"""""""""""""""""""""""""""""

The mappings from user-defined data types in Daml to TypeScript are best explained by example.

Records (a.k.a. product types)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

In Daml, we might model a person like this.

.. code-block:: daml
   :linenos:

   data Person =
     Person with
       name: Text
       party: Party
       age: Int

Given the above definition, the generated TypeScript code will be as follows.

.. code-block:: typescript
   :linenos:

   type Person = {
     name: string;
     party: damlTypes.Party;
     age: damlTypes.Int;
   }

Variants (a.k.a. sum types)
~~~~~~~~~~~~~~~~~~~~~~~~~~~

This is a Daml type for a language of additive expressions.

.. code-block:: daml
   :linenos:

   data Expr a =
       Lit a
     | Var Text
     | Add (Expr a, Expr a)

In TypeScript, it is represented as a `discriminated union <https://www.typescriptlang.org/docs/handbook/typescript-in-5-minutes-func.html#discriminated-unions>`_.

.. code-block:: typescript
   :linenos:

   type Expr<a> =
     |  { tag: 'Lit'; value: a }
     |  { tag: 'Var'; value: string }
     |  { tag: 'Add'; value: Tuple2<Expr<a>, Expr<a>> }

Sum of products
~~~~~~~~~~~~~~~

Let's slightly modify the ``Expr a`` type of the last section into the following.

.. code-block:: daml
   :linenos:

   data Expr a =
       Lit a
     | Var Text
     | Add {lhs: Expr a, rhs: Expr a}

Compared to the earlier definition, the ``Add`` case is now in terms of a record with fields ``lhs`` and ``rhs``. This renders in TypeScript like so.

.. code-block:: typescript
   :linenos:

   type Expr<a> =
     |  { tag: 'Lit'; value: a }
     |  { tag: 'Var'; value: string }
     |  { tag: 'Add'; value: Expr.Add<a> }

   namespace Expr {
     type Add<a> = {
       lhs: Expr<a>;
       rhs: Expr<a>;
     }
   }

Note how the definition of the ``Add`` case has given rise to a record type definition ``Expr.Add``.

Enums
~~~~~

Given a Daml enumeration like this,

.. code-block:: daml
   :linenos:

   data Color = Red | Blue | Yellow

the generated TypeScript will consist of a type declaration and the definition of an associated companion object.

.. code-block:: typescript
   :linenos:

   type Color = 'Red' | 'Blue' | 'Yellow'

   const Color:
     damlTypes.Serializable<Color> & {
     }
   & { readonly keys: Color[] } & { readonly [e in Color]: e };

Templates and Choices
~~~~~~~~~~~~~~~~~~~~~

Here is a Daml template of a basic 'IOU' contract.

.. code-block:: daml
   :linenos:

   template Iou
     with
       issuer: Party
       owner: Party
       currency: Text
       amount: Decimal
     where
       signatory issuer
       choice Transfer: ContractId Iou
         with
           newOwner: Party
         controller owner
         do
           create this with owner = newOwner

The ``daml codegen js`` command generates types for each of the choices defined on the template as well as the template itself.

.. code-block:: typescript
   :linenos:

   type Transfer = {
     newOwner: damlTypes.Party;
   }

   type Iou = {
     issuer: damlTypes.Party;
     owner: damlTypes.Party;
     currency: string;
     amount: damlTypes.Numeric;
   }

Each template results in the generation of an interface and a companion object. Here, is a schematic of the one
generated from the ``Iou`` template [1]_, [2]_.

.. code-block:: typescript
   :linenos:

    interface IouInterface {
      Archive: damlTypes.Choice<Iou, DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Iou, undefined>>;
      Transfer: damlTypes.Choice<Iou, Transfer, damlTypes.ContractId<Iou>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Iou, undefined>>;
    }

    const Iou:
      damlTypes.Template<Iou, undefined, '<template_id>'> &
      damlTypes.ToInterface<Iou, never> &
      IouInterface;

.. [1] The ``undefined`` type parameter captures the fact that ``Iou`` has no contract key.
.. [2] The ``never`` type parameter captures the fact that ``Iou`` does not implement directly any interface.

See the :brokenref:`Use contracts and transactions in JavaScript <link>` for details on how to use generated
code to interact with the JSON Ledger API.
