.. Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
.. SPDX-License-Identifier: Apache-2.0

Releases and Versioning
#######################

.. wip::
   review and ensure that we want to keep giving these guarantees


.. _versioning:

Versioning
**********

All Daml components follow `Semantic Versioning <https://semver.org/>`_. In short, this means that there is a well defined "public API", changes or breakages to which are indicated by the version number.

Stable releases have versions MAJOR.MINOR.PATCH. Segments of the version are incremented according to the following rules:

#. MAJOR version when there are incompatible API changes,
#. MINOR version when functionality is added in a backwards compatible manner, and
#. PATCH version when there are only backwards compatible bug fixes.

.. todo: consider adding back: Daml's "public API" is laid out in the :brokenref:`daml-ecosystem-overview`.

Cadence
*******

Regular, weekly snapshot releases are made every Wednesday, with additional snapshots produced as needed. These releases contain Daml Components, both from the `daml repository <https://github.com/digital-asset/daml>`_ as well as some others.

The decision to perform a Minor version release is based on the content or scope of the payload of that release.  The intent is to release a Minor version once a quarter but this may change based on the customer demand for new, key features.

No more than one major version is released every six months, barring exceptional circumstances.

Individual Daml drivers follow their own release cadence, using already released Integration Components as a dependency.

.. _support_duration:

Support Duration
****************

Major versions will be supported for a minimum of one year after a subsequent Major version is release. Within a major version, security and bug fixes are applied to the latest Minor version as well as the second to last Minor version release, the latter being limited to 6 months after its release or 3 months after the release of the latest Minor version, whichever is longer.

.. _release-notes:

Release Notes
*************

Release notes for each release are published on the `Release Notes section of the Daml Driven blog <https://daml.com/release-notes/>`_.

.. _release_process:

Process
*******

Weekly snapshot and Minor releases follow a common process. The process is documented `in the Daml repository. <https://github.com/digital-asset/daml/blob/main/release/RELEASE.md>`_  Only the schedule for Minor releases is covered below.

Selecting a Release Candidate

  This is done by the Daml core engineering teams.

  The Minor releases are scope-based. Furthermore, Daml development is fully HEAD-based so both the repository and every snapshot are intended to be in a fully releasable state at every point. The release process therefore starts with "selecting a release candidate". Typically the Snapshot from the preceding Wednesday is selected as the release candidate.

Release Notes and Candidate Review

  After selecting the release candidate, Release Notes are written and reviewed with a particular view towards unintended changes and violations of :ref:`Semantic Versioning <versioning>`.

Release Candidate Refinement

  If issues surface in the initial review, the issues are resolved and different Snapshot is selected as the release candidate.

Release Candidate Announcement

  Barring delays due to issues during initial review, the release candidate is announced publicly with accompanying Release Notes.

Communications, Testing and Feedback

  In the days following the announcement, the release is presented and discussed with both commercial and community users. It is also put through its paces by integrating it in `Daml Hub <https://hub.daml.com>`_ and several ledger integrations.

Release Candidate Refinement II

  Depending on feedback and test results, new release candidates may be issued iteratively. Depending on the severity of changes from release candidate to release candidate, the testing period is extended more or less.

Release

  Assuming the release is not postponed due to extended test periods or newly discovered issues in the release candidate, the release is declared stable and given a regular version number.
