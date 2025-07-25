.. Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
.. SPDX-License-Identifier: Apache-2.0

.. _reference-templates:

Reference: Templates
####################

This page gives reference information on templates:

For the structure of a template, see :doc:`structure`.

.. _daml-ref-template-name:

Template Name
*************

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start template intro snippet
   :end-before: -- end template intro snippet

- This is the name of the template. It's preceded by ``template`` keyword. Must begin with a capital letter.
- This is the highest level of nesting.
- The name is used when :ref:`creating <daml-ref-create>` a contract of this template (usually, from within a choice).

.. _daml-ref-template-parameters:

Template Parameters
*******************

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start template params snippet
   :end-before: -- end template params snippet

- ``with`` keyword. The parameters are in the form of a :ref:`record type <daml-ref-record-types>`.
- Passed in when :ref:`creating <daml-ref-create>` a contract from this template. These are then in scope inside the template body.
- A template parameter can't have the same name as any :ref:`choice arguments <daml-ref-choice-arguments>` inside the template.
- For all parties involved in the contract (whether they're a ``signatory``, ``observer``, or ``controller``) you must pass them in as parameters to the contract, whether individually or as a list (``[Party]``).

.. _daml_ref-template-data:

Implicit Record
***************

Whenever a template is defined, a record is implicitly defined with the same
name and fields as that template. This record structure is used in Daml code to
represent the data of a contract based on that template.

Note that in the general case, the existence of a local binding ``b`` of type
``T``, where ``T`` is a template (and thus also a record), does not necessarily
imply the existence of a contract with the same data as ``b`` on the ledger.
You can only assume the existence of such a contract if ``b`` is the result of
a fetch from the ledger within the same transaction.

You can create a new instance of a record of type ``T`` without any interaction
with the ledger; in fact, this is how you construct a create command.

.. _daml_ref-template-this:

``this`` and ``self``
*********************

Within the body of a template we implicitly define a local binding ``this`` to
represent the data of the current contract. For a template ``T``, this binding
is of type ``T``, i.e. the implicit record defined by the template.

Within choices, you can additionally use the binding ``self`` to refer to the
contract ID of the current contract (the one on which the choice is being
executed). For a contract of template ``T``, the ``self`` binding is of type
``ContractId T``.

.. _daml-ref-template-let:

Template-local Definitions (Deprecated)
***************************************

.. todo:: Fix or remove this literal include
.. 'start template let snippet' no longer exists in the daml file
    .. literalinclude:: code-snippets/Reference.daml
       :language: daml
       :start-after: -- start template let snippet
       :end-before: -- end template let snippet

- ``let`` keyword. Starts a block and is followed by any number of definitions, just like any other ``let`` block.
- Template parameters as well as ``this`` are in scope, but ``self`` is not.
- Definitions from the ``let`` block can be used anywhere else in the template's ``where`` block.

.. warning::
   Since Daml 2.8.0, template-local definitions are deprecated and their
   presence will result in the following warning:

   .. code-block:: text

     Template-local binding syntax ("template-let") is deprecated,
     it will be removed in a future version of Daml.
     Instead, use plain top level definitions, taking parameters
     for the contract fields or body ("this") if necessary.

   The reason for this deprecation is that some uses of the ``this`` keyword in
   template-local definitions would create implicit circular dependencies,
   causing an infinite loop upon evaluation.

Migration
=========

Users are strongly encouraged to adapt their code to avoid this feature. This
involves replacing each template-local definition with a regular top-level
definition. If the old definition made use of contract fields or the contract
body ("this"), the new definition should take them as parameters.
Correspondingly, the use sites of these definitions should supply the
appropriate values as arguments.

For example, consider the template ``Person`` below. It defines and uses a
template-local binding ``fullName``, which now triggers the deprecation warning.

.. code-block:: daml

  template Person
    with
      owner : Party
      first : Text
      last : Text
    where
      signatory owner
      let fullName = last <> ", " <> first
      nonconsuming choice GetDescription : ()
        controller owner
        do
          let desc = "An account owned by " <> fullName <> "."
          debug desc

To ensure this code keeps working after the feature is removed, ``fullName``
should be defined as a top-level function, and its use site now passes ``this``
explicitly.

.. code-block:: daml

  fullName : Person -> Text
  fullName Person {first, last} = last <> ", " <> first
  -- takes 'Person' as an explicit parameter and unpacks required fields

  template Person
    with
      owner : Party
      first : Text
      last : Text
    where
      signatory owner
      nonconsuming choice GetDescriptionV3 : ()
        controller owner
        do
          -- let bindings in choice bodies are unaffected
          let desc = "An account owned by " <> fullName this <> "."
                                               -- 'this' is passed explicitly
          debug desc

Turning off the warning
=======================

This warning is controlled by the warning flag ``template-let``, which means
that it can be toggled independently of other warnings. This is especially
useful for gradually migrating code that used this syntax.

To turn off the warning within a Daml file, add the following line at the top of
the file:

.. code-block:: daml

  {-# OPTIONS_GHC -Wno-template-let #-}

To turn it off for an entire Daml project, add the following entry to the
``build-options`` field of the project's ``daml.yaml`` file

.. code-block:: yaml

  build-options:
  - --ghc-option=-Wno-template-let

Within a project where the warning has been turned off via the ``daml.yaml``
file, it can be turned back on for individual Daml files by adding the following
line at the top of each file:

.. code-block:: daml

  {-# OPTIONS_GHC -Wtemplate-let #-}

.. _daml-ref-signatories:

Signatory Parties
*****************

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start template sigs snippet
   :end-before: -- end template sigs snippet

- ``signatory`` keyword. After ``where``. Followed by at least one ``Party``.
- Signatories are the parties (see the ``Party`` type) who must consent to the creation of this contract. They are the parties who would be put into an *obligable position* when this contract is created.

  Daml won't let you put someone into an obligable position without their consent. So if the contract will cause obligations for a party, they *must* be a signatory. **If they haven't authorized it, you won't be able to create the contract.** In this situation, you may see errors like:

  ``NameOfTemplate requires authorizers Party1,Party2,Party, but only Party1 were given.``
- When a signatory consents to the contract creation, this means they also authorize the consequences of :ref:`choices <daml-ref-choices>` that can be exercised on this contract.
- The contract is visible to all signatories (as well as the other stakeholders of the contract). That is, the compiler automatically adds signatories as observers.
- Each template **must** have at least one signatory. A signatory declaration consists of the `signatory` keyword followed by a comma-separated list of one or more expressions, each expression denoting a ``Party`` or collection thereof.

.. _daml-ref-observers:

Observers
*********

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start template obs snippet
   :end-before: -- end template obs snippet

- ``observer`` keyword. After ``where``. Followed by at least one ``Party``.
- Observers are additional stakeholders, so the contract is visible to these parties (see the ``Party`` type).
- Optional. You can have many, either as a comma-separated list or reusing the keyword. You could pass in a list (of type ``[Party]``).
- Use when a party needs visibility on a contract, or be informed or contract events, but is not a :ref:`signatory <daml-ref-signatories>` or :ref:`controller <daml-ref-controllers>`.
- If you start your choice with ``choice`` rather than ``controller`` (see :ref:`daml-ref-choices` below), you must make sure to add any potential controller as an observer. Otherwise, they will not be able to exercise the choice, because they won't be able to see the contract.

.. _daml-ref-choices:

Choices
*******

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start template choice snippet
   :end-before: -- end template choice snippet

- A right that the contract gives the controlling party. Can be *exercised*.
- This is essentially where all the logic of the template goes.
- By default, choices are *consuming*: that is, exercising the choice archives the contract, so no further choices can be exercised on it. You can make a choice non-consuming using the ``nonconsuming`` keyword.
- See :doc:`choices` for full reference information.

.. _daml-ref-serializable-types:

Serializable Types
******************

Every parameter to a template, choice argument, and choice result must have a *serializable type*.
This does not merely mean "convertible to bytes"; it has a specific meaning in Daml.
The serializability rule serves three purposes:

1. Offer a stable means to store ledger values permanently.
2. Provide a sensible encoding of them over the :ref:`ledger-api`.
3. Provide sensible *types* that directly match their Daml counterparts in languages like Java for language codegen.

For example, certain kinds of type parameters Daml offers are compatible with (1) and (2), but have no proper counterpart in (3), so they are disallowed.
Similarly, function types have sensible Java counterparts, satisfying (3), but no reliable way to store or share them via the API, thus failing (1) and (2).

The following types are *not serializable*, and thus may not be used in templates.

- Function types.
- Record types with any non-serializable field.
- Variant types with any non-serializable value case.
- Variant and enum types with no constructors.
- References to a parameterized data type with any non-serializable type argument.
  This applies whether or not the data type definition uses the type parameter.
- Defined data types with any type parameter of kind ``Nat``, or any kind other than ``*``.
  This means higher-kinded types, and types that take a parameter just to pass to ``Numeric``, are not serializable.

Migration
=========

Users should remove any agreement declarations from their code, as this feature has been fully removed from the language.

.. _daml-ref-preconditions:

Preconditions
*************

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start template ensure snippet
   :end-before: -- end template ensure snippet

- ``ensure`` keyword, followed by a boolean condition.
- Used on contract creation. ``ensure`` limits the values on parameters that can be passed to the contract: the contract can only be created if the boolean condition is true.

.. _daml-ref-contract-keys:

.. _daml-ref-maintainers:

Contract Keys and Maintainers
*****************************

.. literalinclude:: code-snippets/Reference.daml
   :language: daml
   :start-after: -- start contract key snippet
   :end-before: -- end contract key snippet

- ``key`` and ``maintainer`` keywords.
- This feature lets you specify a "key" that you can use to uniquely identify this contract as an instance of this template.
- If you specify a ``key``, you must also specify a ``maintainer``. This is a ``Party`` that will ensure the uniqueness of all the keys it is aware of.

  Because of this, the ``key`` must include the ``maintainer`` ``Party`` or parties (for example, as part of a tuple or record), and the ``maintainer`` must be a signatory.
- For a full explanation, see :ref:`contractkeys`.

Interface Instances
*******************

.. literalinclude:: code-snippets-dev/Interfaces.daml
   :language: daml
   :start-after: -- INTERFACE_INSTANCE_IN_TEMPLATE_BEGIN
   :end-before: -- INTERFACE_INSTANCE_IN_TEMPLATE_END

- Used to make a template an instance of an existing interface.
- The clause must start with the keywords ``interface instance``, followed by
  the name of the interface, then the keyword ``for`` and the name of the
  template (which must match the enclosing declaration), and finally the keyword
  ``where``, which introduces a block where **all** the methods of the interface
  must be implemented.
- See :doc:`interfaces` for full reference information on interfaces, or
  section :ref:`interface-instances` for interface instances specifically.
